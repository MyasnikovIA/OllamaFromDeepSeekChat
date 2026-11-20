package ru.miacomsoft.ollama;

import org.json.JSONArray;
import ru.miacomsoft.session.ChatSession;
import ru.miacomsoft.session.SessionManager;

/**
 * Тонкий делегат в SessionManager.
 *
 * Оставлен как «шима» для обратной совместимости с OllamaNativeApi и OllamaV1Api,
 * которые обращаются к OllamaSession.SESSION и OllamaSession.resetIfNewDialog(...).
 *
 * Вся логика живёт в ru.miacomsoft.session.SessionManager.
 * Если хочется убрать этот класс полностью — см. блок «Полный канонический
 * рефакторинг» ниже.
 */
public final class OllamaSession {

    public static final ChatSession SESSION = SessionManager.get();

    private OllamaSession() {}

    public static void resetIfNewDialog(JSONArray messages) {
        SessionManager.resetIfNewDialog(messages);
    }
}