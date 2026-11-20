package ru.miacomsoft;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Мост Java ↔ браузер.
 *
 *   GET  /events?tabId=X  : SSE (server → browser), у каждой вкладки свой tabId
 *   POST /log             : логи, стримы, состояние (browser → server)
 *                            level="hello"     — регистрация вкладки
 *                            level="bye"       — вкладка закрывается (sendBeacon)
 *                            level="mode-state", "token-limit", "stream", "tab-op", …
 *   GET  /tabs            : список активных вкладок (JSON)
 *
 * Команды server → browser:
 *   {type:"send", text, files?}         — отправить сообщение
 *   {type:"eval", code}                 — выполнить JS
 *   {type:"newChat"}                    — новый чат
 *   {type:"enableThinking"|"disableThinking"}
 *   {type:"enableSmartSearch"|"disableSmartSearch"}
 *   {type:"openTab", url, active}       — открыть новую вкладку
 *   {type:"closeTab", tabId}            — закрыть вкладку по tabId
 *   {type:"closeAllTabs"}               — закрыть все вкладки chat.deepseek.com/*
 */
public class BridgeService {

    static final long WATCHDOG_TIMEOUT_MS = 120_000;

    public static final long MAX_FILE_BYTES  = 20L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;

    static final List<Client> clients = new ArrayList<>();
    static final BlockingQueue<PendingCommand> commandQueue = new LinkedBlockingQueue<>();

    static volatile String  activeStreamId      = null;
    static volatile String  activeStreamType    = null;
    static volatile StreamListener activeListener = null;
    static volatile boolean activeFromStdin     = false;
    static volatile boolean activeIsChatCommand = false;
    static volatile boolean awaitingResponse    = false;
    static volatile long    lastActivityAt      = System.currentTimeMillis();

    static volatile String activeServingTabId = null;
    static volatile String selectedTabId      = null;

    static final Object streamLock = new Object();
    static final Set<String> rejectedStreams =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static class Attachment {
        public final String name;
        public final String mimeType;
        public final String dataBase64;
        public Attachment(String name, String mimeType, String dataBase64) {
            this.name = name;
            this.mimeType = mimeType;
            this.dataBase64 = dataBase64;
        }
    }

    public interface StreamListener {
        void onChunk(String text, String type);
        void onDone();
        void onError(String message);
    }

    static class Client {
        final String tabId;
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final long connectedAt = System.currentTimeMillis();
        volatile boolean active = true;
        volatile boolean thinking       = false;
        volatile boolean smartSearch    = false;
        volatile boolean tokenExhausted = false;
        volatile boolean modesReported  = false;
        volatile String  info           = "";
        volatile String  closeReason    = "";
        Client(String tabId) { this.tabId = tabId; }
    }

    static class PendingCommand {
        final String json;
        final StreamListener listener;
        final boolean fromStdin;
        final boolean isChatCommand;
        PendingCommand(String json, StreamListener l, boolean s, boolean c) {
            this.json = json; this.listener = l; this.fromStdin = s; this.isChatCommand = c;
        }
    }

    public static void register(HttpServer server) {
        server.createContext("/events", BridgeService::handleEvents);
        server.createContext("/log",    BridgeService::handleLog);
        server.createContext("/tabs",   BridgeService::handleTabs);
    }

    // ---------- публичный API ----------
    public static void sendFromStdin(String text) {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "send");
        cmd.put("text", text);
        enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendEvalFromStdin(String code) {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "eval");
        cmd.put("code", code);
        enqueue(new PendingCommand(cmd.toString(), null, true, false));
    }

    public static void sendNewChatFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "newChat");
        enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendEnableThinkingFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "enableThinking");
        enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendDisableThinkingFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "disableThinking");
        enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendEnableSmartSearchFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "enableSmartSearch");
        enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendDisableSmartSearchFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "disableSmartSearch");
        enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    // ============================================================
    //  ВОССТАНОВЛЕНО: отправка файла из stdin (было утеряно при
    //  предыдущей правке — из-за этого Main.java не компилировался)
    // ============================================================
    public static void sendFileFromStdin(String path, String text) {
        File f = new File(path);
        if (!f.isFile()) {
            System.out.println();
            System.out.println("[!] file not found: " + path);
            System.out.print("> ");
            System.out.flush();
            return;
        }
        if (f.length() > MAX_FILE_BYTES) {
            System.out.println();
            System.out.println("[!] file too large: " + f.length()
                    + " bytes (max " + MAX_FILE_BYTES + ")");
            System.out.print("> ");
            System.out.flush();
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String b64 = Base64.getEncoder().encodeToString(bytes);
            String mime = Files.probeContentType(f.toPath());
            if (mime == null) mime = guessMimeByName(f.getName());

            JSONObject cmd = new JSONObject();
            cmd.put("type", "send");
            cmd.put("text", text == null ? "" : text);
            JSONArray arr = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("name", f.getName());
            o.put("mimeType", mime);
            o.put("dataBase64", b64);
            arr.put(o);
            cmd.put("files", arr);

            System.out.println();
            System.out.println("[FILE] " + f.getName()
                    + " (" + bytes.length + " bytes, " + mime + ")"
                    + (text != null && !text.isEmpty() ? " → " + text : ""));
            System.out.print("> ");
            System.out.flush();

            enqueue(new PendingCommand(cmd.toString(), null, true, true));
        } catch (Exception e) {
            System.out.println();
            System.out.println("[!] file read error: " + e.getMessage());
            System.out.print("> ");
            System.out.flush();
        }
    }

    // ============================================================
    //  ВОССТАНОВЛЕНО: MIME по расширению
    // ============================================================
    static String guessMimeByName(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp"))  return "image/bmp";
        if (n.endsWith(".pdf"))  return "application/pdf";
        if (n.endsWith(".txt"))  return "text/plain";
        if (n.endsWith(".md"))   return "text/markdown";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".csv"))  return "text/csv";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".doc"))  return "application/msword";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".zip"))  return "application/zip";
        return "application/octet-stream";
    }

    // ============================================================
    //  ВОССТАНОВЛЕНО: sendChat / sendChatStream (нужны OllamaApiService)
    // ============================================================
    public static CompletableFuture<String> sendChat(String text) {
        return sendChat(text, null);
    }

    public static CompletableFuture<String> sendChat(String text, List<Attachment> attachments) {
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder buf = new StringBuilder();
        sendChatStream(text, attachments, new StreamListener() {
            public void onChunk(String t, String type) {
                if ("RESPONSE".equals(type)) buf.append(t);
            }
            public void onDone() { future.complete(buf.toString()); }
            public void onError(String m) { future.completeExceptionally(new RuntimeException(m)); }
        });
        return future;
    }

    public static void sendChatStream(String text, StreamListener listener) {
        sendChatStream(text, null, listener);
    }

    public static void sendChatStream(String text, List<Attachment> attachments, StreamListener listener) {
        long totalB64 = 0;
        if (attachments != null) {
            for (Attachment a : attachments) {
                long bin = a.dataBase64 == null ? 0 : (a.dataBase64.length() * 3L) / 4L;
                if (bin > MAX_FILE_BYTES) {
                    if (listener != null) {
                        listener.onError("file too large: " + a.name
                                + " (" + bin + " > " + MAX_FILE_BYTES + ")");
                    } else {
                        System.out.println();
                        System.out.println("[!] file too large: " + a.name);
                        System.out.print("> "); System.out.flush();
                    }
                    return;
                }
                totalB64 += a.dataBase64 == null ? 0 : a.dataBase64.length();
            }
            long totalBin = (totalB64 * 3L) / 4L;
            if (totalBin > MAX_TOTAL_BYTES) {
                if (listener != null) {
                    listener.onError("attachments total size exceeds " + MAX_TOTAL_BYTES);
                } else {
                    System.out.println();
                    System.out.println("[!] attachments total too large");
                    System.out.print("> "); System.out.flush();
                }
                return;
            }
        }

        JSONObject cmd = new JSONObject();
        cmd.put("type", "send");
        cmd.put("text", text == null ? "" : text);
        if (attachments != null && !attachments.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Attachment a : attachments) {
                JSONObject o = new JSONObject();
                o.put("name", a.name == null ? "file" : a.name);
                o.put("mimeType", a.mimeType == null ? "application/octet-stream" : a.mimeType);
                o.put("dataBase64", a.dataBase64 == null ? "" : a.dataBase64);
                arr.put(o);
            }
            cmd.put("files", arr);
        }
        enqueue(new PendingCommand(cmd.toString(), listener, false, true));
    }

    // ============================================================
    //  НОВОЕ: управление вкладками
    // ============================================================
    public static void openNewTabFromStdin(String url) {
        String target = (url == null || url.trim().isEmpty())
                ? "https://chat.deepseek.com/"
                : url.trim();
        JSONObject cmd = new JSONObject();
        cmd.put("type", "openTab");
        cmd.put("url", target);
        cmd.put("active", true);
        sendFireAndForget(cmd.toString(), target);
    }

    public static void closeTabFromStdin(String targetTabId) {
        if (targetTabId == null || targetTabId.isEmpty()) {
            System.out.println();
            System.out.println("[!] closeTab: empty targetTabId");
            System.out.print("> "); System.out.flush();
            return;
        }
        JSONObject cmd = new JSONObject();
        cmd.put("type", "closeTab");
        cmd.put("tabId", targetTabId);
        sendFireAndForget(cmd.toString(), null);
    }

    public static void closeAllTabsFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "closeAllTabs");
        sendFireAndForget(cmd.toString(), null);
    }

    static void sendFireAndForget(String json, String fallbackUrlForLaunch) {
        Client c = pickAnyActiveClient();
        if (c == null) {
            System.out.println();
            if (fallbackUrlForLaunch != null) {
                System.out.println("[!] no active tab; launching Chrome directly: " + fallbackUrlForLaunch);
                System.out.print("> "); System.out.flush();
                ChromeLauncher.launchNewWindow(fallbackUrlForLaunch);
            } else {
                System.out.println("[!] no active tab to dispatch command");
                System.out.print("> "); System.out.flush();
            }
            return;
        }
        c.queue.offer(json);
    }

    // ---------- утилиты ----------
    public static synchronized void printTabs() {
        List<Client> active = new ArrayList<>();
        synchronized (clients) {
            for (Client c : clients) if (c.active) active.add(c);
        }
        System.out.println();
        System.out.println("[TABS] active: " + active.size()
                + (selectedTabId != null ? " (selected: " + selectedTabId + ")" : " (selected: auto)"));
        for (int i = 0; i < active.size(); i++) {
            Client c = active.get(i);
            String marker = c.tabId.equals(selectedTabId) ? "▶" : " ";
            String status = c.tokenExhausted ? "[EXHAUSTED]" : "[ok]";
            System.out.println("  " + marker + " #" + (i + 1)
                    + " " + c.tabId + " " + status
                    + " thinking=" + c.thinking
                    + " smartSearch=" + c.smartSearch);
            if (c.info != null && !c.info.isEmpty()) {
                System.out.println("      " + c.info);
            }
        }
        System.out.print("> ");
        System.out.flush();
    }

    public static synchronized void printState() {
        Client c = pickActiveClient();
        System.out.println();
        if (c == null) {
            System.out.println("[STATE] no active tab");
        } else {
            System.out.println("[STATE] " + c.tabId
                    + " thinking=" + c.thinking
                    + " smartSearch=" + c.smartSearch
                    + " tokenExhausted=" + c.tokenExhausted);
        }
        System.out.print("> ");
        System.out.flush();
    }

    public static synchronized boolean selectTabByIndex(int idx) {
        List<Client> active = new ArrayList<>();
        synchronized (clients) {
            for (Client c : clients) if (c.active) active.add(c);
        }
        if (idx < 1 || idx > active.size()) return false;
        selectedTabId = active.get(idx - 1).tabId;
        return true;
    }

    public static synchronized boolean selectTabById(String tabId) {
        synchronized (clients) {
            for (Client c : clients) {
                if (c.active && c.tabId.equals(tabId)) {
                    selectedTabId = tabId;
                    return true;
                }
            }
        }
        return false;
    }

    public static synchronized boolean selectTabByPrefix(String prefix) {
        synchronized (clients) {
            for (Client c : clients) {
                if (c.active && c.tabId.startsWith(prefix)) {
                    selectedTabId = c.tabId;
                    return true;
                }
            }
        }
        return false;
    }

    // ---------- НОВОЕ: поиск tabId (для /close) ----------
    public static synchronized String getTabIdByIndex(int idx) {
        List<Client> active = new ArrayList<>();
        synchronized (clients) {
            for (Client c : clients) if (c.active) active.add(c);
        }
        if (idx < 1 || idx > active.size()) return null;
        return active.get(idx - 1).tabId;
    }

    public static synchronized String findActiveTabId(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isEmpty()) return null;
        synchronized (clients) {
            for (Client c : clients) {
                if (c.active && c.tabId.equals(idOrPrefix)) return c.tabId;
            }
            for (Client c : clients) {
                if (c.active && c.tabId.startsWith(idOrPrefix)) return c.tabId;
            }
        }
        return null;
    }

    // ---------- очередь ----------
    static synchronized void enqueue(PendingCommand cmd) {
        commandQueue.offer(cmd);
        trySendNext();
    }

    static synchronized void trySendNext() {
        if (awaitingResponse) return;
        Client c = pickActiveClient();
        if (c == null) return;
        PendingCommand cmd = commandQueue.poll();
        if (cmd == null) return;
        awaitingResponse = true;
        activeListener = cmd.listener;
        activeFromStdin = cmd.fromStdin;
        activeIsChatCommand = cmd.isChatCommand;
        activeServingTabId = c.tabId;
        lastActivityAt = System.currentTimeMillis();
        c.queue.offer(cmd.json);
    }

    static Client pickActiveClient() {
        synchronized (clients) {
            if (selectedTabId != null) {
                for (Client c : clients) {
                    if (c.active && c.tabId.equals(selectedTabId) && !c.tokenExhausted) {
                        return c;
                    }
                }
                selectedTabId = null;
            }
            for (Client c : clients) {
                if (c.active && !c.tokenExhausted) return c;
            }
            return null;
        }
    }

    static Client pickAnyActiveClient() {
        synchronized (clients) {
            if (selectedTabId != null) {
                for (Client c : clients) {
                    if (c.active && c.tabId.equals(selectedTabId)) return c;
                }
            }
            for (Client c : clients) {
                if (c.active) return c;
            }
        }
        return null;
    }

    public static void watchdogTick() {
        if (awaitingResponse
                && System.currentTimeMillis() - lastActivityAt > WATCHDOG_TIMEOUT_MS) {
            StreamListener l = activeListener;
            activeListener = null;
            awaitingResponse = false;
            activeFromStdin = false;
            activeIsChatCommand = false;
            activeServingTabId = null;
            if (l != null) {
                try { l.onError("watchdog timeout"); } catch (Exception ignored) {}
            }
            trySendNext();
        }
    }

    static void setCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    static String queryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (pair.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    static Client findClient(String tabId) {
        if (tabId == null || tabId.isEmpty()) return null;
        synchronized (clients) {
            for (Client c : clients) {
                if (c.active && c.tabId.equals(tabId)) return c;
            }
        }
        return null;
    }

    // ---------- GET /events ----------
    static void handleEvents(HttpExchange ex) throws IOException {
        setCors(ex);
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1); ex.close(); return;
        }

        String tabId = queryParam(ex.getRequestURI().getQuery(), "tabId");
        if (tabId == null || tabId.isEmpty()) {
            tabId = "anon-" + System.nanoTime();
        }

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        Client client = new Client(tabId);
        synchronized (clients) { clients.add(client); }

        trySendNext();

        final String myTabId = tabId;
        try {
            os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            while (client.active) {
                String msg = client.queue.poll(5, TimeUnit.SECONDS);
                if (msg == null) {
                    os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    os.write(("data: " + msg + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                os.flush();
            }
        } catch (InterruptedException ignored) {
        } catch (IOException ignored) {
        } finally {
            client.active = false;
            synchronized (clients) { clients.remove(client); }
            synchronized (BridgeService.class) {
                if (myTabId.equals(selectedTabId))      selectedTabId = null;
                if (myTabId.equals(activeServingTabId)) {
                    activeServingTabId = null;
                    // не сбрасываем awaitingResponse — пусть watchdog обработает
                }
            }
            try { ex.close(); } catch (Exception ignore) {}
            trySendNext();
        }
    }

    // ---------- POST /log ----------
    static void handleLog(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1); ex.close(); return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1); ex.close(); return;
        }

        String body;
        try (InputStream is = ex.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        try {
            JSONObject json = new JSONObject(body);
            String level  = json.optString("level", "log");
            String text   = json.optString("text", "");
            String sid    = json.optString("streamId", "");
            String sType  = json.optString("streamType", "RESPONSE");
            String tabId  = json.optString("tabId", "");
            lastActivityAt = System.currentTimeMillis();

            switch (level) {
                case "stream":      handleStreamChunk(sid, text, sType); break;
                case "stream-done": handleStreamDone(sid); break;
                case "result":      handleResult(text); break;
                case "error":       handleError(text); break;
                case "hello":       handleHello(tabId, text); break;
                case "mode-state":  handleModeState(tabId, text); break;
                case "token-limit": handleTokenLimit(tabId, text); break;
                case "bye":         handleBye(tabId, text); break;
                case "tab-op":      handleTabOp(text); break;              // НОВОЕ
                case "console-error":
                    System.out.println();
                    System.out.println("[EXT-ERR] " + text);
                    System.out.print("> "); System.out.flush();
                    break;
            }
        } catch (Exception e) {
            System.err.println("[log] " + e.getMessage());
        }

        byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, ok.length);
        ex.getResponseBody().write(ok);
        ex.close();
    }

    // НОВОЕ: результат операции над вкладкой
    static void handleTabOp(String text) {
        System.out.println();
        System.out.println("[TABOP] " + (text == null ? "" : text));
        System.out.print("> ");
        System.out.flush();
    }

    // обработка закрытия вкладки
    static void handleBye(String tabId, String reason) {
        if (tabId == null || tabId.isEmpty()) return;

        Client c = findClient(tabId);
        if (c != null) {
            c.closeReason = reason == null ? "" : reason;
            c.active = false;
            c.queue.offer("__close__");
        }

        synchronized (clients) { clients.remove(c); }

        boolean wasSelected = tabId.equals(selectedTabId);
        boolean wasServing  = tabId.equals(activeServingTabId);

        if (wasSelected) selectedTabId = null;

        if (wasServing) {
            StreamListener l = activeListener;
            boolean wasStdin = activeFromStdin;
            activeListener = null;
            awaitingResponse = false;
            activeFromStdin = false;
            activeIsChatCommand = false;
            activeServingTabId = null;
            activeStreamId = null;
            activeStreamType = null;

            if (l != null) {
                try { l.onError("tab closed: " + shortId(tabId)); } catch (Exception ignored) {}
            } else if (wasStdin) {
                System.out.println();
                System.out.println("[!] tab closed: " + shortId(tabId));
                System.out.print("> ");
                System.out.flush();
            }
        }

        System.out.println();
        System.out.println("[BYE] tab " + shortId(tabId)
                + " closed" + (reason != null && !reason.isEmpty() ? " (" + reason + ")" : ""));
        System.out.print("> ");
        System.out.flush();

        trySendNext();
    }

    static void handleHello(String tabId, String info) {
        Client c = findClient(tabId);
        if (c == null) return;
        c.info = info;
        System.out.println();
        System.out.println("[TAB] registered: " + tabId);
        if (info != null && !info.isEmpty()) {
            System.out.println("      " + info);
        }
        System.out.print("> ");
        System.out.flush();
    }

    static void handleModeState(String tabId, String text) {
        Client c = findClient(tabId);
        if (c == null) return;
        try {
            JSONObject j = new JSONObject(text);
            boolean thinking    = j.optBoolean("thinking", false);
            boolean smartSearch = j.optBoolean("smartSearch", false);
            boolean changed = !c.modesReported
                    || (c.thinking != thinking)
                    || (c.smartSearch != smartSearch);
            c.thinking    = thinking;
            c.smartSearch = smartSearch;
            if (changed) {
                c.modesReported = true;
                System.out.println();
                System.out.println("[MODE " + shortId(tabId) + "]"
                        + " thinking=" + thinking
                        + " smartSearch=" + smartSearch);
                System.out.print("> ");
                System.out.flush();
            }
        } catch (Exception ignored) {}
    }

    static void handleTokenLimit(String tabId, String resetOn) {
        Client c = findClient(tabId);
        if (c == null) return;
        boolean wasFirstTime = !c.tokenExhausted;
        c.tokenExhausted = true;

        boolean wasServing = tabId.equals(activeServingTabId) && awaitingResponse;
        StreamListener l = null;
        boolean wasStdin = false;

        if (wasServing) {
            synchronized (streamLock) {
                l = activeListener;
                wasStdin = activeFromStdin;
                activeListener = null;
                awaitingResponse = false;
                activeFromStdin = false;
                activeIsChatCommand = false;
                activeServingTabId = null;
                activeStreamId = null;
                activeStreamType = null;
            }
        }

        if (wasFirstTime) {
            System.out.println();
            System.out.println("[LIMIT] tab " + shortId(tabId)
                    + " hit usage limit. Reset: " + (resetOn.isEmpty() ? "?" : resetOn));
            System.out.print("> ");
            System.out.flush();
        }

        if (wasServing) {
            if (tabId.equals(selectedTabId)) selectedTabId = null;
            if (l != null) {
                try { l.onError("usage limit reached: " + resetOn); } catch (Exception ignored) {}
            } else if (wasStdin) {
                System.out.println();
                System.out.println("[!] usage limit reached: " + resetOn);
                System.out.print("> ");
                System.out.flush();
            }
            trySendNext();
        }
    }

    static String shortId(String tabId) {
        if (tabId == null) return "?";
        return tabId.length() > 12 ? tabId.substring(0, 12) + "…" : tabId;
    }

    static void handleStreamChunk(String sid, String text, String sType) {
        if (sid.isEmpty()) return;
        synchronized (streamLock) {
            if (rejectedStreams.contains(sid)) return;

            if (activeStreamId == null) {
                activeStreamId = sid;
                activeStreamType = null;
            } else if (!activeStreamId.equals(sid)) {
                rejectedStreams.add(sid);
                return;
            }

            if (!sType.equals(activeStreamType)) {
                if (activeStreamType != null) System.out.println();
                System.out.print("THINK".equals(sType) ? "[THINK] " : "[DEEPSEEK] ");
                activeStreamType = sType;
            }
            System.out.print(text);
            System.out.flush();
        }

        StreamListener l = activeListener;
        if (l != null) {
            try { l.onChunk(text, sType); } catch (Exception ignored) {}
        }
    }

    static void handleStreamDone(String sid) {
        if (sid.isEmpty()) return;
        StreamListener l = null;
        boolean wasStdin = false;

        synchronized (streamLock) {
            if (rejectedStreams.remove(sid)) return;
            boolean wasActive = sid.equals(activeStreamId);
            boolean orphan = (activeStreamId == null && awaitingResponse);

            if (wasActive || orphan) {
                if (wasActive && activeStreamType != null) System.out.println();
                activeStreamId = null;
                activeStreamType = null;
                activeServingTabId = null;
                l = activeListener;
                wasStdin = activeFromStdin;
                activeListener = null;
                awaitingResponse = false;
                activeFromStdin = false;
                activeIsChatCommand = false;
            }
        }

        if (l != null) {
            try { l.onDone(); } catch (Exception ignored) {}
        }

        trySendNext();

        if (wasStdin) {
            System.out.print("> ");
            System.out.flush();
        }
    }

    static void handleResult(String text) {
        if (text.startsWith("sent:")) return;

        StreamListener l = activeListener;
        boolean wasStdin = activeFromStdin;
        activeListener = null;
        awaitingResponse = false;
        activeFromStdin = false;
        activeIsChatCommand = false;
        activeServingTabId = null;

        if (l != null) {
            try { l.onError(text); } catch (Exception ignored) {}
        } else if (wasStdin) {
            System.out.println();
            System.out.println("✓ " + text);
            System.out.print("> ");
            System.out.flush();
        }

        trySendNext();
    }

    static void handleError(String text) {
        StreamListener l = activeListener;
        boolean wasStdin = activeFromStdin;
        activeListener = null;
        awaitingResponse = false;
        activeFromStdin = false;
        activeIsChatCommand = false;
        activeServingTabId = null;

        if (l != null) {
            try { l.onError(text); } catch (Exception ignored) {}
        } else if (wasStdin) {
            System.out.println();
            System.out.println("[!] " + text);
            System.out.print("> ");
            System.out.flush();
        }
        trySendNext();
    }

    // ---------- GET /tabs ----------
    static void handleTabs(HttpExchange ex) throws IOException {
        setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1); ex.close(); return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1); ex.close(); return;
        }

        JSONArray arr = new JSONArray();
        synchronized (clients) {
            for (Client c : clients) {
                if (!c.active) continue;
                JSONObject o = new JSONObject();
                o.put("tabId", c.tabId);
                o.put("info", c.info);
                o.put("thinking", c.thinking);
                o.put("smartSearch", c.smartSearch);
                o.put("tokenExhausted", c.tokenExhausted);
                o.put("selected", c.tabId.equals(selectedTabId));
                arr.put(o);
            }
        }
        JSONObject resp = new JSONObject();
        resp.put("count", arr.length());
        resp.put("tabs", arr);
        byte[] b = resp.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}