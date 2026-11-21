package ru.miacomsoft.bridge;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;

/**
 * POST /api/ajax
 *
 * Полностью серверный AJAX-мост. Запрос выполняет САМ СЕРВЕР
 * через java.net.http.HttpClient — браузер/плагин НЕ участвуют.
 *
 * Формат запроса:
 *   {
 *     "url":       "https://…",           // обязателен
 *     "method":    "GET" | "POST" | …,    // по умолчанию GET
 *     "headers":   { "K": "V", … },       // опционально
 *     "body":      "…",                   // для POST/PUT/PATCH
 *     "timeoutMs": 60000                  // опционально
 *   }
 *
 * Формат ответа:
 *   { "ok": true,  "result": { status, headers, body, url } }
 *   { "ok": false, "error": "…" }
 *
 * CORS разрешён (BridgeUtils.setCors) — вызывается из любого origin.
 */
public final class ApiAjaxHandler {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private ApiAjaxHandler() {}

    public static void handle(HttpExchange ex) throws IOException {
        BridgeUtils.setCors(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1); ex.close(); return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ApiCommandHandler.writeJson(ex, 405, ApiCommandHandler.errorObj("method not allowed"));
            return;
        }

        String body;
        try (InputStream is = ex.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        JSONObject req;
        try { req = new JSONObject(body); }
        catch (Exception e) {
            ApiCommandHandler.writeJson(ex, 400, ApiCommandHandler.errorObj("invalid json"));
            return;
        }

        String url = req.optString("url", "").trim();
        if (url.isEmpty()) {
            ApiCommandHandler.writeJson(ex, 400, ApiCommandHandler.errorObj("empty 'url'"));
            return;
        }

        URI uri;
        try { uri = URI.create(url); }
        catch (Exception e) {
            ApiCommandHandler.writeJson(ex, 400,
                    ApiCommandHandler.errorObj("invalid url: " + e.getMessage()));
            return;
        }

        String method = req.optString("method", "GET").toUpperCase();
        long timeoutMs = req.optLong("timeoutMs", 60_000L);
        String reqBody = req.optString("body", "");
        JSONObject headers = req.optJSONObject("headers");

        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .method(method, reqBody.isEmpty()
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8));

            if (headers != null) {
                for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
                    String k = it.next();
                    String v = headers.optString(k, "");
                    try { rb.header(k, v); } catch (Exception ignore) {}
                }
            }

            HttpResponse<String> resp = HTTP.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            JSONObject hdrs = new JSONObject();
            for (Map.Entry<String, java.util.List<String>> e : resp.headers().map().entrySet()) {
                if (!e.getValue().isEmpty()) hdrs.put(e.getKey(), e.getValue().get(0));
            }

            JSONObject payload = new JSONObject();
            payload.put("status",  resp.statusCode());
            payload.put("headers", hdrs);
            payload.put("body",    resp.body() == null ? "" : resp.body());
            payload.put("url",     uri.toString());

            ApiCommandHandler.writeJson(ex, 200, ApiCommandHandler.okObj(payload));
        } catch (Exception e) {
            ApiCommandHandler.writeJson(ex, 502,
                    ApiCommandHandler.errorObj("ajax failed: " + e.getMessage()));
        }
    }
}