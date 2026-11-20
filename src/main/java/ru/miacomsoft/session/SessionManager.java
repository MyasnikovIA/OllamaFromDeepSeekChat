package ru.miacomsoft.session;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.bridge.BridgeCommands;

/**
 * Единственный владелец ChatSession в приложении + логика «новый диалог».
 *
 * Раньше эта роль была размазана:
 *   - Main.session         — сам объект ChatSession
 *   - OllamaSession.SESSION = Main.session
 *   - OllamaSession.resetIfNewDialog(messages) — детектор нового диалога
 *
 * Теперь:
 *   SessionManager.get()                     → тот же самый ChatSession
 *   SessionManager.resetIfNewDialog(msgs)    → детектор + отправка newChat в браузер
 */
public final class SessionManager {

    private static final ChatSession SESSION = new ChatSession();

    private SessionManager() {}

    // ------------------------------------------------------------
    //  доступ к хранилищу
    // ------------------------------------------------------------
    public static ChatSession get() {
        return SESSION;
    }

    // ------------------------------------------------------------
    //  делегаты, чтобы не тащить .get().… по всему коду
    // ------------------------------------------------------------
    public static void clearHistory() {
        SESSION.clearHistory();
    }

    public static void addMessage(String role, String content) {
        SESSION.addMessage(role, content);
    }

    public static int getHistorySize() {
        return SESSION.getHistorySize();
    }

    // ------------------------------------------------------------
    //  «это новый диалог?»
    // ------------------------------------------------------------
    /**
     * Если во входящем массиве messages ровно одно user-сообщение,
     * а в истории уже что-то есть — считаем это новым диалогом:
     * чистим историю и просим браузер создать новый чат.
     */
    public static void resetIfNewDialog(JSONArray messages) {
        if (messages == null) return;

        int userMsgCount = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null && "user".equals(m.optString("role"))) userMsgCount++;
        }

        if (userMsgCount <= 1 && SESSION.getHistorySize() > 0) {
            SESSION.clearHistory();
            BridgeCommands.sendNewChatFromStdin();
        }
    }
}