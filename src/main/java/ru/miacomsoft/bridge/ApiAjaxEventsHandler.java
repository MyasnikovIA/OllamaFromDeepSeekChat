package ru.miacomsoft.bridge;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * GET /api/ajax/events?tabId=X
 *
 * Отдельный SSE-канал для AJAX-команд. Устроен как BridgeEventsHandler,
 * но работает с другой очередью BrowserTab — используется
 * только для type="ajax" / "ajax-result".
 *
 * На одном BrowserTab.queue висят и обычные команды (send/eval/...),
 * и AJAX-запросы — это нормально: content.js сам различает их по полю type.
 */
public final class ApiAjaxEventsHandler {

    private ApiAjaxEventsHandler() {}

    public static void handle(HttpExchange ex) throws IOException {
        BridgeUtils.setCors(ex);
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1); ex.close(); return;
        }

        String tabId = BridgeUtils.queryParam(ex.getRequestURI().getQuery(), "tabId");
        if (tabId == null || tabId.isEmpty()) {
            tabId = "anon-ajax-" + System.nanoTime();
        }

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        BrowserTab client = new BrowserTab(tabId);
        TabRegistry.registerSse(client);

        final String myTabId = tabId;
        try {
            os.write(": ajax-connected\n\n".getBytes(StandardCharsets.UTF_8));
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
            TabRegistry.removeClient(client);
            TabRegistry.clearSelectedIf(myTabId);
            CommandDispatcher.clearServingTabIf(myTabId);
            try { ex.close(); } catch (Exception ignore) {}
        }
    }
}