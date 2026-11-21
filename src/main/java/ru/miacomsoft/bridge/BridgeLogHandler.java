package ru.miacomsoft.bridge;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** POST /log — события от вкладок браузера (stream, hello, bye, …). */
public final class BridgeLogHandler {

    private BridgeLogHandler() {}

    public static void handle(HttpExchange ex) throws IOException {
        BridgeUtils.setCors(ex);
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
            CommandDispatcher.touchActivity();

            switch (level) {
                case "stream":        CommandDispatcher.onStreamChunk(sid, text, sType); break;
                case "stream-done":   CommandDispatcher.onStreamDone(sid); break;
                case "result":        CommandDispatcher.onResult(text); break;
                case "error":         CommandDispatcher.onError(text); break;
                case "hello":         TabRegistry.onHello(tabId, text); break;
                case "mode-state":    TabRegistry.onModeState(tabId, text); break;
                case "token-limit":   CommandDispatcher.onTokenLimit(tabId, text); break;
                case "bye":           CommandDispatcher.onBye(tabId, text); break;
                case "tab-op":        printTabOp(text); break;
                case "ajax-result":   onAjaxResult(json); break;   // ← НОВОЕ
                case "console-error":
                    ConsoleWriter.get().ensureNewline();
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

    private static void printTabOp(String text) {
        ConsoleWriter.get().ensureNewline();
        System.out.println("[TABOP] " + (text == null ? "" : text));
        System.out.print("> "); System.out.flush();
    }
    /**
     * Пришёл результат AJAX-запроса от вкладки.
     * Ожидаем: { level:"ajax-result", requestId, ok, status, headers, body, url, error? }
     */
    private static void onAjaxResult(JSONObject json) {
        String requestId = json.optString("requestId", "");
        if (requestId.isEmpty()) return;

        JSONObject result = new JSONObject();
        result.put("ok",      json.optBoolean("ok", false));
        result.put("status",  json.optInt("status", 0));
        result.put("headers", json.optJSONObject("headers") == null
                ? new JSONObject() : json.optJSONObject("headers"));
        result.put("body",    json.optString("body", ""));
        result.put("url",     json.optString("url", ""));
        if (json.has("error")) result.put("error", json.optString("error", ""));

        AjaxRegistry.complete(requestId, result);
    }
}