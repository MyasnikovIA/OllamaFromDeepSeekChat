package ru.miacomsoft.bridge;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.ChromeLauncher;
import ru.miacomsoft.config.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Collections;
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
    //  НОВОЕ: detach — отменить все висящие прикрепления
    // ============================================================
    public static void sendDetachFromStdin() {
        ConsoleWriter cw = ConsoleWriter.get();
        cw.ensureNewline();
        System.out.println("[DETACH] отмена всех прикреплённых файлов…");
        System.out.print("> "); System.out.flush();

        JSONObject cmd = new JSONObject();
        cmd.put("type", "detach");
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, false));
    }

    /**
     * Для ApiCommandHandler: отправить detach и дождаться результата.
     */
    public static void sendDetachForApi(StreamListener listener) {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "detach");
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), listener, false, false));
    }

    // ============================================================
    //  НОВОЕ: sendWithAttached — отправить текст с уже прикреплёнными файлами
    // ============================================================
    /**
     * Отправляет текст, НЕ прикрепляя файлы (они уже в composer).
     * Используется, когда пользователь прикрепил файлы вручную
     * и хочет только ввести текст + нажать «Отправить».
     */
    public static void sendWithAttachedFromStdin(String text) {
        ConsoleWriter cw = ConsoleWriter.get();
        cw.ensureNewline();
        System.out.println("[SEND-WITH-ATTACHED] " + (text == null ? "" : text));
        System.out.print("> "); System.out.flush();

        JSONObject cmd = new JSONObject();
        cmd.put("type", "sendWithAttached");
        cmd.put("text", text == null ? "" : text);
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    /**
     * Для ApiCommandHandler: то же, но с ожиданием результата.
     */
    public static void sendWithAttachedForApi(String text, StreamListener listener) {
        JSONObject cmd = new JSONObject();
        cmd.put("type", "sendWithAttached");
        cmd.put("text", text == null ? "" : text);
        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), listener, false, true));
    }

    // ============================================================
    //  Чтение файла с диска → Attachment
    // ============================================================
    public static Attachment readFileAttachment(String path) {
        ConsoleWriter cw = ConsoleWriter.get();
        File f = new File(path);
        if (!f.isFile()) {
            cw.ensureNewline();
            System.out.println("[!] file not found: " + path);
            System.out.print("> "); System.out.flush();
            return null;
        }
        if (f.length() > BridgeConstants.MAX_FILE_BYTES) {
            cw.ensureNewline();
            System.out.println("[!] file too large: " + f.length()
                    + " bytes (max " + BridgeConstants.MAX_FILE_BYTES + ")");
            System.out.print("> "); System.out.flush();
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String b64 = Base64.getEncoder().encodeToString(bytes);
            String mime = Files.probeContentType(f.toPath());
            if (mime == null) mime = BridgeUtils.guessMimeByName(f.getName());
            return new Attachment(f.getName(), mime, b64);
        } catch (Exception e) {
            cw.ensureNewline();
            System.out.println("[!] file read error: " + e.getMessage());
            System.out.print("> "); System.out.flush();
            return null;
        }
    }

    public static Attachment readFileAttachmentForApi(String path) {
        File f = new File(path);
        if (!f.isFile()) return null;
        if (f.length() > BridgeConstants.MAX_FILE_BYTES) return null;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String b64 = Base64.getEncoder().encodeToString(bytes);
            String mime = Files.probeContentType(f.toPath());
            if (mime == null) mime = BridgeUtils.guessMimeByName(f.getName());
            return new Attachment(f.getName(), mime, b64);
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    //  stdin: /file (send с вложением)
    // ============================================================
    public static void sendFileFromStdin(String path, String text) {
        Attachment a = readFileAttachment(path);
        if (a == null) return;

        JSONObject cmd = new JSONObject();
        cmd.put("type", "send");
        cmd.put("text", text == null ? "" : text);
        JSONArray arr = new JSONArray();
        JSONObject o = new JSONObject();
        o.put("name", a.name);
        o.put("mimeType", a.mimeType);
        o.put("dataBase64", a.dataBase64);
        arr.put(o);
        cmd.put("files", arr);

        ConsoleWriter cw = ConsoleWriter.get();
        cw.ensureNewline();
        System.out.println("[FILE] " + a.name
                + " (" + a.dataBase64.length() + " b64 chars, " + a.mimeType + ")"
                + (text != null && !text.isEmpty() ? " → " + text : ""));
        System.out.print("> "); System.out.flush();

        CommandDispatcher.enqueue(new PendingCommand(cmd.toString(), null, true, true));
    }

    // ============================================================
    //  Clipboard API
    // ============================================================
    public static void sendClipboardCommand(String type,
                                            List<Attachment> attachments,
                                            StreamListener listener) {
        if (type == null || type.isEmpty()) {
            if (listener != null) listener.onError("empty clipboard command type");
            else printConsoleError("empty clipboard command type");
            return;
        }

        boolean hasFiles = attachments != null && !attachments.isEmpty();
        boolean isPaste   = "clipboardPaste".equals(type);

        if (!isPaste && !hasFiles) {
            if (listener != null) listener.onError("no attachments for " + type);
            else printConsoleError("no attachments for " + type);
            return;
        }

        if (hasFiles) {
            long totalB64 = 0;
            for (Attachment a : attachments) {
                long bin = a.dataBase64 == null ? 0 : (a.dataBase64.length() * 3L) / 4L;
                if (bin > BridgeConstants.MAX_FILE_BYTES) {
                    String msg = "file too large: " + a.name
                            + " (" + bin + " > " + BridgeConstants.MAX_FILE_BYTES + ")";
                    if (listener != null) listener.onError(msg);
                    else printConsoleError(msg);
                    return;
                }
                totalB64 += a.dataBase64 == null ? 0 : a.dataBase64.length();
            }
            long totalBin = (totalB64 * 3L) / 4L;
            if (totalBin > BridgeConstants.MAX_TOTAL_BYTES) {
                String msg = "attachments total size exceeds "
                        + BridgeConstants.MAX_TOTAL_BYTES;
                if (listener != null) listener.onError(msg);
                else printConsoleError(msg);
                return;
            }
        }

        JSONObject cmd = new JSONObject();
        cmd.put("type", type);

        if (hasFiles) {
            JSONArray arr = new JSONArray();
            for (Attachment a : attachments) {
                JSONObject o = new JSONObject();
                o.put("name", a.name == null ? "file" : a.name);
                o.put("mimeType", a.mimeType == null
                        ? "application/octet-stream" : a.mimeType);
                o.put("dataBase64", a.dataBase64 == null ? "" : a.dataBase64);
                arr.put(o);
            }
            cmd.put("files", arr);
        }

        boolean fromStdin = (listener == null);
        CommandDispatcher.enqueue(
                new PendingCommand(cmd.toString(), listener, fromStdin, false)
        );
    }

    public static void sendClipboardAttachFromStdin(String path) {
        Attachment a = readFileAttachment(path);
        if (a == null) return;

        ConsoleWriter cw = ConsoleWriter.get();
        cw.ensureNewline();
        System.out.println("[CLIPATTACH] " + a.name
                + " (" + a.dataBase64.length() + " b64 chars, " + a.mimeType + ")");
        System.out.print("> "); System.out.flush();

        sendClipboardCommand("clipboardAttach",
                Collections.singletonList(a), null);
    }

    public static void sendClipboardCopyFromStdin(String path) {
        Attachment a = readFileAttachment(path);
        if (a == null) return;

        ConsoleWriter cw = ConsoleWriter.get();
        cw.ensureNewline();
        System.out.println("[CLIPCOPY] " + a.name
                + " (" + a.dataBase64.length() + " b64 chars, " + a.mimeType + ")");
        System.out.print("> "); System.out.flush();

        sendClipboardCommand("clipboardCopy",
                Collections.singletonList(a), null);
    }

    public static void sendClipboardPasteFromStdin() {
        ConsoleWriter cw = ConsoleWriter.get();
        cw.ensureNewline();
        System.out.println("[CLIPPASTE] вставка из буфера обмена…");
        System.out.print("> "); System.out.flush();

        sendClipboardCommand("clipboardPaste", null, null);
    }

    // ============================================================
    //  chat API (для ollama)
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

    // Старая сигнатура — дефолтные флаги (обратная совместимость)
    public static void sendChatStream(String text, List<Attachment> attachments, StreamListener listener) {
        sendChatStream(text, attachments, listener, false, false, false, true);
    }

    // Сигнатура с clipboard (обратная совместимость с Ответом 3)
    public static void sendChatStream(String text, List<Attachment> attachments,
                                      StreamListener listener, boolean clipboard) {
        sendChatStream(text, attachments, listener, clipboard, false, false, true);
    }

    /**
     * Полная сигнатура с флагами.
     *
     * @param clipboard    файлы через clipboard (а не через input[type=file])
     * @param strict       не отправлять, если attach провалился
     * @param skipAttach   не прикреплять файлы (они уже в composer)
     * @param setTextFirst true — сначала текст, потом файлы (по умолчанию)
     */
    public static void sendChatStream(String text, List<Attachment> attachments,
                                      StreamListener listener,
                                      boolean clipboard,
                                      boolean strict,
                                      boolean skipAttach,
                                      boolean setTextFirst) {
        long totalB64 = 0;
        if (attachments != null && !skipAttach) {
            for (Attachment a : attachments) {
                long bin = a.dataBase64 == null ? 0 : (a.dataBase64.length() * 3L) / 4L;
                if (bin > BridgeConstants.MAX_FILE_BYTES) {
                    if (listener != null) listener.onError("file too large: " + a.name + " (" + bin + " > " + BridgeConstants.MAX_FILE_BYTES + ")");
                    else printConsoleError("file too large: " + a.name);
                    return;
                }
                totalB64 += a.dataBase64 == null ? 0 : a.dataBase64.length();
            }
            long totalBin = (totalB64 * 3L) / 4L;
            if (totalBin > BridgeConstants.MAX_TOTAL_BYTES) {
                if (listener != null) listener.onError("attachments total size exceeds " + BridgeConstants.MAX_TOTAL_BYTES);
                else printConsoleError("attachments total too large");
                return;
            }
        }

        JSONObject cmd = new JSONObject();
        cmd.put("type", "send");
        cmd.put("text", text == null ? "" : text);
        if (clipboard) cmd.put("clipboard", true);
        if (strict) cmd.put("strict", true);
        if (skipAttach) cmd.put("skipAttach", true);
        if (!setTextFirst) cmd.put("setTextFirst", false); // default true — не шлём

        if (attachments != null && !attachments.isEmpty() && !skipAttach) {
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
        String target = (url == null || url.trim().isEmpty()) ? "https://chat.deepseek.com/" : url.trim();
        JSONObject cmd = new JSONObject();
        cmd.put("type", "openTab");
        cmd.put("url", target);
        cmd.put("active", true);
        sendFireAndForget(cmd.toString(), target);
    }

    public static void closeTabFromStdin(String targetTabId) {
        if (targetTabId == null || targetTabId.isEmpty()) {
            printConsoleError("closeTab: empty targetTabId");
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

    // ============================================================
    //  запуск браузера по индексу
    // ============================================================
    public static void launchBrowserFromStdin(int index) {
        Config cfg = Config.get();
        List<ru.miacomsoft.config.BrowserConfig> browsers = cfg.getBrowsers();

        if (browsers == null || index < 0 || index >= browsers.size()) {
            ConsoleWriter.get().ensureNewline();
            System.out.println("[!] Invalid browser index: " + index
                    + " (total: " + (browsers == null ? 0 : browsers.size()) + ")");
            System.out.print("> "); System.out.flush();
            return;
        }

        ru.miacomsoft.config.BrowserConfig bc = browsers.get(index);
        ConsoleWriter.get().ensureNewline();
        System.out.println("[CHROME] Launching browser #" + index + ": " + bc.displayName());
        System.out.print("> "); System.out.flush();

        new Thread(() -> {
            ChromeLauncher.launchNewWindow(cfg.getDeepseekUrl(), bc);
        }).start();
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
    //  helpers
    // ============================================================
    public static void printTabs()  { TabRegistry.printTabs(); }
    public static void printState() { TabRegistry.printState(); }
    public static boolean selectTabByIndex(int idx)    { return TabRegistry.selectTabByIndex(idx); }
    public static boolean selectTabById(String tabId)  { return TabRegistry.selectTabById(tabId); }
    public static boolean selectTabByPrefix(String p)  { return TabRegistry.selectTabByPrefix(p); }
    public static String  getTabIdByIndex(int idx)     { return TabRegistry.getTabIdByIndex(idx); }
    public static String  findActiveTabId(String q)    { return TabRegistry.findActiveTabId(q); }
    public static void    watchdogTick()               { CommandDispatcher.watchdogTick(); }

    private static void printConsoleError(String msg) {
        ConsoleWriter.get().ensureNewline();
        System.out.println("[!] " + msg);
        System.out.print("> "); System.out.flush();
    }
}