package ru.miacomsoft.bridge;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * POST /api/command
 *
 * Принимает JSON-команду, ставит её в CommandDispatcher и ждёт ответа.
 * Каждая команда ЭХОМ печатается в консоль сервера в формате stdin
 * ("> /think on", "> /stop", "> send: <текст>", "> /tabs", …),
 * чтобы оператор видел в консоли то же, что и при ручном вводе.
 *
 * Поддерживаемые type:
 *   send, eval, newChat,
 *   enableThinking, disableThinking,
 *   enableSmartSearch, disableSmartSearch,
 *   stopGeneration, resumeGeneration,
 *   openTab, closeTab, closeAllTabs,
 *   tabs, state, tab, file, help
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
                if (text.isEmpty()) { writeJson(ex, 400, errorObj("empty 'text'")); return; }
                echo("send: " + text);
                awaitCommand(ex, cmd("send").put("text", text), 120_000L);
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
                JSONObject c = cmd("closeTab").put("tabId", tabId);
                BridgeCommands.sendFireAndForget(c.toString(), null);
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
                // printTabs сам печатает [TABS] … + "> "
                TabRegistry.printTabs();
                writeJson(ex, 200, okObj(TabRegistry.toJson()));
                return;
            }
            case "state": {
                echo("/state");
                // printState сам печатает [STATE] … + "> "
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
                if (target.matches("\\d+")) {
                    ok = TabRegistry.selectTabByIndex(Integer.parseInt(target));
                } else {
                    ok = TabRegistry.selectTabById(target) || TabRegistry.selectTabByPrefix(target);
                }
                ConsoleWriter.get().ensureNewline();
                System.out.println("[TAB] selected: " + (ok ? "yes" : "no") + " (" + target + ")");
                System.out.print("> "); System.out.flush();
                writeJson(ex, 200, okObj(new JSONObject().put("selected", ok).put("tabId", target)));
                return;
            }
            case "help": {
                echo("/help");
                JSONObject help = new JSONObject();
                JSONArray cmds = new JSONArray();
                cmds.put("send     → {type:\"send\", text:\"...\"}");
                cmds.put("eval     → {type:\"eval\", code:\"...\"}");
                cmds.put("newChat  → {type:\"newChat\"}");
                cmds.put("thinking → {type:\"enableThinking\"|\"disableThinking\"}");
                cmds.put("search   → {type:\"enableSmartSearch\"|\"disableSmartSearch\"}");
                cmds.put("stop     → {type:\"stopGeneration\"}");
                cmds.put("resume   → {type:\"resumeGeneration\"}");
                cmds.put("openTab  → {type:\"openTab\", url?, active?}");
                cmds.put("closeTab → {type:\"closeTab\", tabId:\"...\"}");
                cmds.put("closeAll → {type:\"closeAllTabs\"}");
                cmds.put("tabs     → {type:\"tabs\"}");
                cmds.put("state    → {type:\"state\"}");
                cmds.put("tab      → {type:\"tab\", tabId:\"#n|id|prefix\"}");
                cmds.put("file     → {type:\"file\", path:\"...\", text?}");
                help.put("endpoints", new JSONArray()
                        .put("POST /api/command")
                        .put("GET  /tabs")
                        .put("GET  /api/ajax")
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

    /** Эхо команды в консоль сервера — как будто её ввели через stdin. */
    private static void echo(String line) {
        ConsoleWriter cw = ConsoleWriter.get();
        cw.ensureNewline();
        System.out.println("> " + line);
        System.out.flush();
    }

    /** Создать JSONObject команды с заданным type. */
    private static JSONObject cmd(String type) {
        JSONObject o = new JSONObject();
        o.put("type", type);
        return o;
    }

    /** Кладёт команду в очередь и блокируется до onDone/onError. */
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

        // fromStdin=true → CommandDispatcher напечатает "> " после onStreamDone.
        CommandDispatcher.enqueue(new PendingCommand(cmdObj.toString(), listener, true, true));

        JSONObject result;
        try {
            result = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            writeJson(ex, 504, errorObj("timeout waiting for command result"));
            return;
        }

        if (result.optBoolean("done", false)) {
            writeJson(ex, 200, okObj(result));
        } else {
            writeJson(ex, 500, result);
        }
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