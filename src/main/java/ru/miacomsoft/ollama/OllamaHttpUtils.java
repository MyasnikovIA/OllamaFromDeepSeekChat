package ru.miacomsoft.ollama;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * HTTP-хелперы для всех хендлеров Ollama / OpenAI-совместимого API.
 */
public final class OllamaHttpUtils {

    private OllamaHttpUtils() {}

    public static void setCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    public static void noContent(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    public static void methodNotAllowed(HttpExchange ex) throws IOException {
        writeError(ex, 405, "method not allowed");
    }

    public static void writeJson(HttpExchange ex, int status, JSONObject obj) throws IOException {
        byte[] b = obj.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    public static void writeError(HttpExchange ex, int status, String msg) throws IOException {
        JSONObject o = new JSONObject();
        o.put("error", msg);
        byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    public static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            logIncomingRequest(ex, body);
            return body;
        }
    }

    public static void logIncomingRequest(HttpExchange ex, String body) {
        if (!OllamaConstants.DEBUG) return;

        String method = ex.getRequestMethod();
        String uri = ex.getRequestURI().toString();

        System.out.println();
        System.out.println("[INCOMING] " + method + " " + uri);

        if (body != null && !body.trim().isEmpty()) {
            String shownBody = body.length() > 2000
                    ? body.substring(0, 2000) + "... [truncated, total " + body.length() + " chars]"
                    : body;
            System.out.println("[INCOMING] Body: " + shownBody);
        }
        System.out.print("> ");
        System.out.flush();
    }

    public static String nowIso() {
        return Instant.now().toString();
    }
}