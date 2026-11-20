package ru.miacomsoft.bridge;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.ChromeLauncher;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Публичный API моста — статические фасады для Main.java и ollama.*.
 */
public final class BridgeCommands {

    private BridgeCommands() {}

    // ============================================================
    //  stdin API
    // ============================================================
    public static void sendFromStdin(String text) {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "send");
        cmd.put("text", text);
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendEvalFromStdin(String code) {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "eval");
        cmd.put("code", code);
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, false));
    }

    public static void sendNewChatFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "newChat");
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendEnableThinkingFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "enableThinking");
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendDisableThinkingFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "disableThinking");
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendEnableSmartSearchFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "enableSmartSearch");
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    public static void sendDisableSmartSearchFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "disableSmartSearch");
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    // ============================================================
    //  НОВОЕ: стоп / продолжить
    //
    //  Эти команды идут через sendFireAndForget — они не должны
    //  блокироваться текущим awaitingResponse, иначе ими нельзя
    //  было бы прервать уже идущую генерацию.
    //
    //  Завершение остановленного стрима приходит как обычный
    //  stream-done (DeepSeek закрывает fetch по клику на «Стоп»),
    //  что штатно снимает awaitingResponse. Watchdog остаётся
    //  fallback'ом на случай, если по какой-то причине stream-done
    //  не пришёл (120 сек).
    // ============================================================
    public static void sendStopFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "stopGeneration");
        sendFireAndForget(cmd.toString(), null);
    }

    public static void sendResumeFromStdin() {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "resumeGeneration");
        sendFireAndForget(cmd.toString(), null);
    }

    // ============================================================
    //  sendFileFromStdin
    // ============================================================
    public static void sendFileFromStdin(String path, String text) {
        ConsoleWriter cw = ConsoleWriter.get();
        File f = new File(path);
        if (!f.isFile()) {
            cw.ensureNewline();
            System.out.println("[!] file not found: " + path);
            System.out.print("> "); System.out.flush();
            return;
        }
        if (f.length() > BridgeConstants.MAX_FILE_BYTES) {
            cw.ensureNewline();
            System.out.println("[!] file too large: " + f.length()
                    + " bytes (max " + BridgeConstants.MAX_FILE_BYTES + ")");
            System.out.print("> "); System.out.flush();
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
            String mime = Files.probeContentType(f.toPath());
            if (mime == null) mime = BridgeUtils.guessMimeByName(f.getName());

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

            cw.ensureNewline();
            System.out.println("[FILE] " + f.getName()
                    + " (" + bytes.length + " bytes, " + mime + ")"
                    + (text != null && !text.isEmpty() ? " → " + text : ""));
            System.out.print("> "); System.out.flush();

            CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, true));
        } catch (Exception e) {
            cw.ensureNewline();
            System.out.println("[!] file read error: " + e.getMessage());
            System.out.print("> "); System.out.flush();
        }
    }

    // ============================================================
    //  chat API (используется пакетом ollama)
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
                if (bin > BridgeConstants.MAX_FILE_BYTES) {
                    if (listener != null) {
                        listener.onError("file too large: " + a.name
                                + " (" + bin + " > " + BridgeConstants.MAX_FILE_BYTES + ")");
                    } else {
                        ConsoleWriter.get().ensureNewline();
                        System.out.println("[!] file too large: " + a.name);
                        System.out.print("> "); System.out.flush();
                    }
                    return;
                }
                totalB64 += a.dataBase64 == null ? 0 : a.dataBase64.length();
            }
            long totalBin = (totalB64 * 3L) / 4L;
            if (totalBin > BridgeConstants.MAX_TOTAL_BYTES) {
                if (listener != null) {
                    listener.onError("attachments total size exceeds " + BridgeConstants.MAX_TOTAL_BYTES);
                } else {
                    ConsoleWriter.get().ensureNewline();
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
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), listener, false, true));
    }

    // ============================================================
    //  tab management API (stdin)
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
            ConsoleWriter.get().ensureNewline();
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
        BrowserTab c = TabRegistry.pickAnyActive();
        if (c == null) {
            ConsoleWriter.get().ensureNewline();
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

    // ============================================================
    //  helpers для Main (делегаты в TabRegistry / CommandDispatcher)
    // ============================================================
    public static void printTabs()  { TabRegistry.printTabs(); }
    public static void printState() { TabRegistry.printState(); }
    public static boolean selectTabByIndex(int idx)    { return TabRegistry.selectTabByIndex(idx); }
    public static boolean selectTabById(String tabId)  { return TabRegistry.selectTabById(tabId); }
    public static boolean selectTabByPrefix(String p)  { return TabRegistry.selectTabByPrefix(p); }
    public static String  getTabIdByIndex(int idx)     { return TabRegistry.getTabIdByIndex(idx); }
    public static String  findActiveTabId(String q)    { return TabRegistry.findActiveTabId(q); }
    public static void    watchdogTick()               { CommandDispatcher.watchdogTick(); }
}