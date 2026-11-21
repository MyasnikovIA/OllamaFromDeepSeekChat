package ru.miacomsoft.bridge;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр «ожидающих» AJAX-запросов.
 *
 * Клиент делает POST /api/ajax и ждёт JSON-ответ.
 * Реальный запрос исполняет браузерная вкладка (content.js)
 * и присылает результат обратно на POST /log с level="ajax-result".
 *
 * Связка: requestId (UUID) → AjaxPending (future + метаданные).
 */
public final class AjaxRegistry {

    public static final class AjaxPending {
        public final String  requestId;
        public final long    startedAt  = System.currentTimeMillis();
        public final long    timeoutMs;
        public volatile boolean finished = false;
        public volatile JSONObject result;   // заполняется из /log level="ajax-result"

        AjaxPending(String requestId, long timeoutMs) {
            this.requestId = requestId;
            this.timeoutMs = timeoutMs;
        }
    }

    private static final Map<String, AjaxPending> pending = new ConcurrentHashMap<>();

    private AjaxRegistry() {}

    public static AjaxPending register(String requestId, long timeoutMs) {
        AjaxPending p = new AjaxPending(requestId, timeoutMs);
        pending.put(requestId, p);
        return p;
    }

    public static AjaxPending get(String requestId) {
        return requestId == null ? null : pending.get(requestId);
    }

    public static void remove(String requestId) {
        if (requestId != null) pending.remove(requestId);
    }

    /**
     * Пришёл результат от вкладки. Будим ожидающего.
     * Вызывается из BridgeLogHandler при level="ajax-result".
     */
    public static void complete(String requestId, JSONObject result) {
        AjaxPending p = get(requestId);
        if (p == null) return;
        synchronized (p) {
            if (p.finished) return;
            p.result = result;
            p.finished = true;
            p.notifyAll();
        }
    }

    /**
     * Дождаться результата (или таймаута). Возвращает null при таймауте.
     * Снимает регистрацию в любом случае.
     */
    public static JSONObject await(AjaxPending p) {
        if (p == null) return null;
        long deadline = p.startedAt + p.timeoutMs;
        try {
            synchronized (p) {
                while (!p.finished) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) return null;
                    p.wait(left);
                }
                return p.result;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            remove(p.requestId);
        }
    }
}