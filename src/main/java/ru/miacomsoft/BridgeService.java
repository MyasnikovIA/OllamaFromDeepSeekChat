package ru.miacomsoft;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 *   GET  /events : SSE (server → browser)
 *   POST /log    : логи и стримы (browser → server)
 *
 * Публичный API:
 *   - sendFromStdin(text)               — sendToDeepSeek
 *   - sendEvalFromStdin(code)           — eval
 *   - sendNewChatFromStdin()            — новый чат
 *   - sendEnableThinkingFromStdin()     — включить режим размышления
 *   - sendDisableThinkingFromStdin()    — выключить режим размышления
 *   - sendChat(text)                    — CompletableFuture<String>
 *   - sendChatStream(text, listener)    — стрим в слушатель
 */
public class BridgeService {

    static final long WATCHDOG_TIMEOUT_MS = 120_000;

    static final List<Client> clients = new ArrayList<>();
    static final BlockingQueue<PendingCommand> commandQueue = new LinkedBlockingQueue<>();

    static volatile String activeStreamId = null;
    static volatile String activeStreamType = null;
    static volatile StreamListener activeListener = null;
    static volatile boolean activeFromStdin = false;
    static volatile boolean activeIsChatCommand = false;   // «пользовательская» команда (send / newChat / thinking)
    static volatile boolean awaitingResponse = false;
    static volatile long lastActivityAt = System.currentTimeMillis();

    static final Object streamLock = new Object();
    static final Set<String> rejectedStreams =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public interface StreamListener {
        void onChunk(String text, String type);
        void onDone();
        void onError(String message);
    }

    static class Client {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final long connectedAt = System.currentTimeMillis();
        volatile boolean active = true;
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

    public static CompletableFuture<String> sendChat(String text) {
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder buf = new StringBuilder();
        sendChatStream(text, new StreamListener() {
            public void onChunk(String t, String type) {
                if ("RESPONSE".equals(type)) buf.append(t);
            }
            public void onDone() { future.complete(buf.toString()); }
            public void onError(String m) { future.completeExceptionally(new RuntimeException(m)); }
        });
        return future;
    }

    public static void sendChatStream(String text, StreamListener listener) {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "send");
        cmd.put("text", text);
        enqueue(new PendingCommand(cmd.toString(), listener, false, true));
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
        lastActivityAt = System.currentTimeMillis();
        c.queue.offer(cmd.json);
    }

    static Client pickActiveClient() {
        synchronized (clients) {
            Client best = null;
            for (Client c : clients) {
                if (!c.active) continue;
                if (best == null || c.connectedAt > best.connectedAt) best = c;
            }
            return best;
        }
    }

    public static void watchdogTick() {
        if (awaitingResponse
                && System.currentTimeMillis() - lastActivityAt > WATCHDOG_TIMEOUT_MS) {
            StreamListener l = activeListener;
            activeListener = null;
            awaitingResponse = false;
            activeFromStdin = false;
            activeIsChatCommand = false;
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

    // ---------- GET /events ----------
    static void handleEvents(HttpExchange ex) throws IOException {
        setCors(ex);
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1); ex.close(); return;
        }

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        Client client = new Client();
        synchronized (clients) { clients.add(client); }

        trySendNext();

        try {
            os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            while (client.active) {
                String msg = client.queue.poll(15, TimeUnit.SECONDS);
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
            try { ex.close(); } catch (Exception ignore) {}
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
            lastActivityAt = System.currentTimeMillis();

            switch (level) {
                case "stream":      handleStreamChunk(sid, text, sType); break;
                case "stream-done": handleStreamDone(sid); break;
                case "result":      handleResult(text); break;
                case "error":       handleError(text); break;
            }
        } catch (Exception ignored) {
        }

        byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, ok.length);
        ex.getResponseBody().write(ok);
        ex.close();
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
        // "sent: ..." — техническое подтверждение, не печатаем, но и не завершаем —
        // стрим продолжается, и завершится на stream-done
        if (text.startsWith("sent:")) return;

        StreamListener l = activeListener;
        boolean wasStdin = activeFromStdin;
        activeListener = null;
        awaitingResponse = false;
        activeFromStdin = false;
        activeIsChatCommand = false;

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
}