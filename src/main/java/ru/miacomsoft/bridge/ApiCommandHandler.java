package ru.miacomsoft.bridge;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.config.BrowserConfig;
import ru.miacomsoft.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * POST /api/command
 *
 * Поддерживаемые type:
 *   send, sendWithAttached, detach,
 *   attach, setText, appendText, clickSend,
 *   clipboardAttach, clipboardCopy, clipboardPaste,
 *   eval, newChat, enableThinking, disableThinking,
 *   enableSmartSearch, disableSmartSearch,
 *   stopGeneration, resumeGeneration,
 *   openTab, closeTab, closeAllTabs,
 *   tabs, state, getState, tab, file, help, browsers, openBrowser
 */
public final class ApiCommandHandler {

    private ApiCommandHandler() {}

    public static void handle(HttpExchange ex) throws IOException {
        BridgeUtils.setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1); ex.close(); return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            writeJson(ex, 405, errorObj("method not allowed"));
            return;
        }

        String body;
        try (InputStream is = ex.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        JSONObject req;
        try { req = new JSONObject(body); }
        catch (Exception e) { writeJson(ex, 400, errorObj("invalid json")); return; }

        String type = req.optString("type", "").trim();
        if (type.isEmpty()) { writeJson(ex, 400, errorObj("missing 'type'")); return; }

        try {
            dispatch(ex, req, type);
        } catch (Exception e) {
            writeJson(ex, 500, errorObj(String.valueOf(e.getMessage())));
        }
    }

    private static void dispatch(HttpExchange ex, JSONObject req, String type) throws Exception {
        switch (type) {
            case "send": {
                String text = req.optString("text", "");
                JSONArray filesArr = req.optJSONArray("files");
                boolean hasFiles = filesArr != null && filesArr.length() > 0;
                if (text.isEmpty() && !hasFiles) {
                    writeJson(ex, 400, errorObj("empty 'text' and no 'files'"));
                    return;
                }

                boolean clip         = req.optBoolean("clipboard", false);
                boolean strict       = req.optBoolean("strict", false);
                boolean skipAttach   = req.optBoolean("skipAttach", false);
                boolean setTextFirst = req.optBoolean("setTextFirst", true);

                echo("send" + (clip ? " [clipboard]" : "")
                        + (skipAttach ? " [skipAttach]" : "")
                        + (strict ? " [strict]" : "")
                        + ": " + text);

                JSONObject c = cmd("send").put("text", text);
                if (clip) c.put("clipboard", true);
                if (strict) c.put("strict", true);
                if (skipAttach) c.put("skipAttach", true);
                if (!setTextFirst) c.put("setTextFirst", false);
                if (filesArr != null) c.put("files", filesArr);

                awaitCommand(ex, c, 180_000L);
                return;
            }

            // ===== sendWithAttached =====
            case "sendWithAttached": {
                String text = req.optString("text", "");
                if (text.isEmpty()) {
                    writeJson(ex, 400, errorObj("empty 'text'"));
                    return;
                }
                echo("/sendwithattached " + text);

                CompletableFuture<JSONObject> fut = new CompletableFuture<>();
                StreamListener listener = new StreamListener() {
                    @Override public void onChunk(String t, String st) { /* nope */ }
                    @Override public void onDone() {
                        fut.complete(new JSONObject().put("done", true).put("type", "sendWithAttached"));
                    }
                    @Override public void onError(String m) {
                        fut.complete(errorObj(m));
                    }
                };
                BridgeCommands.sendWithAttachedForApi(text, listener);

                JSONObject result;
                try {
                    result = fut.get(180_000L, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    writeJson(ex, 504, errorObj("timeout waiting for sendWithAttached"));
                    return;
                }
                if (result.optBoolean("done", false)) {
                    writeJson(ex, 200, okObj(result));
                } else {
                    writeJson(ex, 500, result);
                }
                return;
            }

            // ===== detach =====
            case "detach": {
                echo("/detach");

                CompletableFuture<JSONObject> fut = new CompletableFuture<>();
                StreamListener listener = new StreamListener() {
                    @Override public void onChunk(String t, String st) { /* nope */ }
                    @Override public void onDone() {
                        fut.complete(new JSONObject().put("done", true).put("type", "detach"));
                    }
                    @Override public void onError(String m) {
                        fut.complete(errorObj(m));
                    }
                };
                BridgeCommands.sendDetachForApi(listener);

                JSONObject result;
                try {
                    result = fut.get(30_000L, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    writeJson(ex, 504, errorObj("timeout waiting for detach"));
                    return;
                }
                if (result.optBoolean("done", false)) {
                    writeJson(ex, 200, okObj(result));
                } else {
                    writeJson(ex, 500, result);
                }
                return;
            }

            case "attach": {
                JSONArray filesArr = req.optJSONArray("files");
                if (filesArr == null || filesArr.length() == 0) {
                    writeJson(ex, 400, errorObj("empty 'files'"));
                    return;
                }
                echo("/attach " + filesArr.length() + " file(s)");
                JSONObject c = cmd("attach").put("files", filesArr);
                awaitCommand(ex, c, 60_000L);
                return;
            }

            case "setText": {
                String text = req.optString("text", "");
                if (text.isEmpty()) { writeJson(ex, 400, errorObj("empty 'text'")); return; }
                echo("/settext " + text);
                awaitCommand(ex, cmd("setText").put("text", text), 30_000L);
                return;
            }

            case "appendText": {
                String text = req.optString("text", "");
                if (text.isEmpty()) { writeJson(ex, 400, errorObj("empty 'text'")); return; }
                echo("/append " + text);
                awaitCommand(ex, cmd("appendText").put("text", text), 30_000L);
                return;
            }

            case "clickSend": {
                echo("/clicksend");
                awaitCommand(ex, cmd("clickSend"), 30_000L);
                return;
            }

            case "eval": {
                String code = req.optString("code", "");
                if (code.isEmpty()) { writeJson(ex, 400, errorObj("empty 'code'")); return; }
                echo("! " + code);
                awaitCommand(ex, cmd("eval").put("code", code), 60_000L);
                return;
            }
            case "newChat": {
                echo("/new");
                awaitCommand(ex, cmd("newChat"), 60_000L);
                return;
            }
            case "enableThinking": {
                echo("/think on");
                awaitCommand(ex, cmd("enableThinking"), 60_000L);
                return;
            }
            case "disableThinking": {
                echo("/think off");
                awaitCommand(ex, cmd("disableThinking"), 60_000L);
                return;
            }
            case "enableSmartSearch": {
                echo("/search on");
                awaitCommand(ex, cmd("enableSmartSearch"), 60_000L);
                return;
            }
            case "disableSmartSearch": {
                echo("/search off");
                awaitCommand(ex, cmd("disableSmartSearch"), 60_000L);
                return;
            }
            case "stopGeneration": {
                echo("/stop");
                awaitCommand(ex, cmd("stopGeneration"), 30_000L);
                return;
            }
            case "resumeGeneration": {
                echo("/resume");
                awaitCommand(ex, cmd("resumeGeneration"), 30_000L);
                return;
            }
            case "file": {
                String path = req.optString("path", "");
                String text = req.optString("text", "");
                if (path.isEmpty()) { writeJson(ex, 400, errorObj("empty 'path'")); return; }
                echo("/file " + path + (text.isEmpty() ? "" : " " + text));
                BridgeCommands.sendFileFromStdin(path, text);
                writeJson(ex, 200, okObj(new JSONObject().put("queued", true)));
                return;
            }
            case "clipboardAttach":
            case "clipboardCopy": {
                List<Attachment> atts = parseAttachments(req, ex);
                if (atts == null) return;
                if (atts.isEmpty()) {
                    writeJson(ex, 400, errorObj("empty 'files' (or 'path') for " + type));
                    return;
                }
                echo("clipboardAttach".equals(type)
                        ? "/clipattach " + atts.size() + " file(s)"
                        : "/clipcopy " + atts.size() + " file(s)");
                awaitClipboardCommand(ex, type, atts);
                return;
            }
            case "clipboardPaste": {
                echo("/clippaste");
                awaitClipboardCommand(ex, "clipboardPaste", null);
                return;
            }
            case "openTab": {
                String url = req.optString("url", "https://chat.deepseek.com/");
                boolean active = req.optBoolean("active", true);
                echo("/opentab " + url);
                JSONObject c = cmd("openTab").put("url", url).put("active", active);
                BridgeCommands.sendFireAndForget(c.toString(), url);
                writeJson(ex, 200, okObj(new JSONObject().put("queued", true).put("url", url)));
                return;
            }
            case "closeTab": {
                String tabId = req.optString("tabId", "");
                if (tabId.isEmpty()) { writeJson(ex, 400, errorObj("empty 'tabId'")); return; }
                echo("/close " + tabId);
                BridgeCommands.sendFireAndForget(cmd("closeTab").put("tabId", tabId).toString(), null);
                writeJson(ex, 200, okObj(new JSONObject().put("queued", true).put("tabId", tabId)));
                return;
            }
            case "closeAllTabs": {
                echo("/closeall");
                BridgeCommands.sendFireAndForget(cmd("closeAllTabs").toString(), null);
                writeJson(ex, 200, okObj(new JSONObject().put("queued", true)));
                return;
            }
            case "tabs": {
                echo("/tabs");
                TabRegistry.printTabs();
                writeJson(ex, 200, okObj(TabRegistry.toJson()));
                return;
            }
            case "state":
            case "getState": {
                echo("/state");
                TabRegistry.printState();
                JSONObject resp = new JSONObject();
                BrowserTab c = TabRegistry.pickActiveForDispatch();
                if (c == null) {
                    resp.put("active", JSONObject.NULL);
                } else {
                    JSONObject o = new JSONObject();
                    o.put("tabId", c.tabId);
                    o.put("info", c.info);
                    o.put("thinking", c.thinking);
                    o.put("smartSearch", c.smartSearch);
                    o.put("tokenExhausted", c.tokenExhausted);
                    resp.put("active", o);
                }
                writeJson(ex, 200, okObj(resp));
                return;
            }
            case "tab": {
                String target = req.optString("tabId", "");
                if (target.isEmpty()) { writeJson(ex, 400, errorObj("empty 'tabId'")); return; }
                echo("/tab " + target);
                boolean ok;
                if (target.matches("\\d+")) ok = TabRegistry.selectTabByIndex(Integer.parseInt(target));
                else                         ok = TabRegistry.selectTabById(target) || TabRegistry.selectTabByPrefix(target);
                ConsoleWriter.get().ensureNewline();
                System.out.println("[TAB] selected: " + (ok ? "yes" : "no") + " (" + target + ")");
                System.out.print("> "); System.out.flush();
                writeJson(ex, 200, okObj(new JSONObject().put("selected", ok).put("tabId", target)));
                return;
            }
            case "browsers": {
                echo("/browsers");
                Config cfg = Config.get();
                List<BrowserConfig> list = cfg.getBrowsers();
                JSONArray arr = new JSONArray();
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        JSONObject b = new JSONObject();
                        b.put("index", i);
                        b.put("userDataDir", list.get(i).userDataDir);
                        b.put("displayName", list.get(i).displayName());
                        b.put("valid", list.get(i).isValid());
                        arr.put(b);
                    }
                }
                JSONObject resp = new JSONObject();
                resp.put("count", arr.length());
                resp.put("browsers", arr);
                writeJson(ex, 200, okObj(resp));
                return;
            }
            case "openBrowser": {
                int index = req.optInt("index", -1);
                if (index < 0) { writeJson(ex, 400, errorObj("missing or invalid 'index'")); return; }
                echo("/openbrowser " + index);
                BridgeCommands.launchBrowserFromStdin(index);
                writeJson(ex, 200, okObj(new JSONObject().put("queued", true).put("index", index)));
                return;
            }
            case "help": {
                echo("/help");
                JSONObject help = new JSONObject();
                JSONArray cmds = new JSONArray();
                cmds.put("send          → {type:\"send\", text?, files?, clipboard?, strict?, skipAttach?, setTextFirst?}");
                cmds.put("sendAttached  → {type:\"sendWithAttached\", text}");
                cmds.put("detach        → {type:\"detach\"}");
                cmds.put("attach        → {type:\"attach\", files:[...]}");
                cmds.put("setText       → {type:\"setText\", text:\"...\"}");
                cmds.put("appendText    → {type:\"appendText\", text:\"...\"}");
                cmds.put("clickSend     → {type:\"clickSend\"}");
                cmds.put("clipAttach    → {type:\"clipboardAttach\", files:[...]|path:\"...\"}");
                cmds.put("clipCopy      → {type:\"clipboardCopy\", files:[...]|path:\"...\"}");
                cmds.put("clipPaste     → {type:\"clipboardPaste\"}");
                cmds.put("eval          → {type:\"eval\", code:\"...\"}");
                cmds.put("newChat       → {type:\"newChat\"}");
                cmds.put("thinking      → {type:\"enableThinking\"|\"disableThinking\"}");
                cmds.put("search        → {type:\"enableSmartSearch\"|\"disableSmartSearch\"}");
                cmds.put("stop          → {type:\"stopGeneration\"}");
                cmds.put("resume        → {type:\"resumeGeneration\"}");
                cmds.put("openTab       → {type:\"openTab\", url?, active?}");
                cmds.put("closeTab      → {type:\"closeTab\", tabId:\"...\"}");
                cmds.put("closeAll      → {type:\"closeAllTabs\"}");
                cmds.put("tabs          → {type:\"tabs\"}");
                cmds.put("state         → {type:\"state\"| \"getState\"}");
                cmds.put("tab           → {type:\"tab\", tabId:\"#n|id|prefix\"}");
                cmds.put("file          → {type:\"file\", path:\"...\", text?}");
                cmds.put("browsers      → {type:\"browsers\"}");
                cmds.put("openBrowser   → {type:\"openBrowser\", index:0}");
                help.put("endpoints", new JSONArray()
                        .put("POST /api/command")
                        .put("GET  /tabs")
                        .put("POST /api/ajax"));
                help.put("commands", cmds);
                writeJson(ex, 200, okObj(help));
                return;
            }
            default:
                writeJson(ex, 400, errorObj("unknown type: " + type));
        }
    }

    // ============================================================
    //  helpers
    // ============================================================
    private static void echo(String line) {
        ConsoleWriter cw = ConsoleWriter.get();
        cw.ensureNewline();
        System.out.println("> " + line);
        System.out.flush();
    }

    private static JSONObject cmd(String type) {
        JSONObject o = new JSONObject();
        o.put("type", type);
        return o;
    }

    private static void awaitCommand(HttpExchange ex, JSONObject cmdObj, long timeoutMs) throws Exception {
        CompletableFuture<JSONObject> fut = new CompletableFuture<>();

        StreamListener listener = new StreamListener() {
            @Override public void onChunk(String text, String type) { /* не нужно */ }
            @Override public void onDone() {
                fut.complete(new JSONObject().put("done", true));
            }
            @Override public void onError(String message) {
                fut.complete(errorObj(message));
            }
        };

        CommandDispatcher.enqueue(new PendingCommand(cmdObj.toString(), listener, true, true));

        JSONObject result;
        try {
            result = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            writeJson(ex, 504, errorObj("timeout waiting for command result"));
            return;
        }

        if (result.optBoolean("done", false)) {
            writeJson(ex, 200, okObj(result));
        } else {
            writeJson(ex, 500, result);
        }
    }

    private static void awaitClipboardCommand(HttpExchange ex,
                                              String type,
                                              List<Attachment> atts) throws Exception {
        CompletableFuture<JSONObject> fut = new CompletableFuture<>();

        StreamListener listener = new StreamListener() {
            @Override public void onChunk(String text, String streamType) { /* не нужно */ }
            @Override public void onDone() {
                fut.complete(new JSONObject().put("done", true).put("type", type));
            }
            @Override public void onError(String message) {
                fut.complete(errorObj(message));
            }
        };

        BridgeCommands.sendClipboardCommand(type, atts, listener);

        JSONObject result;
        try {
            result = fut.get(120_000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            writeJson(ex, 504, errorObj("timeout waiting for clipboard result"));
            return;
        }

        if (result.optBoolean("done", false)) {
            writeJson(ex, 200, okObj(result));
        } else {
            writeJson(ex, 500, result);
        }
    }

    private static List<Attachment> parseAttachments(JSONObject req, HttpExchange ex) throws IOException {
        List<Attachment> atts = new ArrayList<>();

        JSONArray filesArr = req.optJSONArray("files");
        if (filesArr != null) {
            for (int i = 0; i < filesArr.length(); i++) {
                JSONObject f = filesArr.optJSONObject(i);
                if (f == null) continue;
                String name = f.optString("name", "file.bin");
                String mime = f.optString("mimeType", f.optString("mime", "application/octet-stream"));
                String b64  = f.optString("dataBase64", "");
                if (b64.isEmpty()) {
                    writeJson(ex, 400, errorObj("file #" + i + " has empty dataBase64"));
                    return null;
                }
                atts.add(new Attachment(name, mime, b64));
            }
            return atts;
        }

        String path = req.optString("path", "").trim();
        if (!path.isEmpty()) {
            Attachment a = BridgeCommands.readFileAttachmentForApi(path);
            if (a == null) {
                writeJson(ex, 404, errorObj("file not found or unreadable: " + path));
                return null;
            }
            atts.add(a);
            return atts;
        }

        return atts;
    }

    static void writeJson(HttpExchange ex, int status, JSONObject obj) throws IOException {
        byte[] b = obj.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        ex.close();
    }

    static JSONObject okObj(JSONObject result) {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("result", result == null ? JSONObject.NULL : result);
        return o;
    }

    static JSONObject errorObj(String msg) {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", msg == null ? "unknown error" : msg);
        return o;
    }
}