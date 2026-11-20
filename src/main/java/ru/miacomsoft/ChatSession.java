package ru.miacomsoft;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class ChatSession {
    private final List<JSONObject> messageHistory = new ArrayList<>();
    private final String systemPrompt = "You are a helpful assistant.";

    public synchronized void clearHistory() {
        messageHistory.clear();
        System.out.println("[SESSION] История сообщений очищена.");
    }

    public synchronized void addMessage(String role, String content) {
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        msg.put("content", content);
        messageHistory.add(msg);
    }

    public synchronized int getHistorySize() {
        return messageHistory.size();
    }

    // Проверяем, является ли запрос началом нового диалога
    public synchronized boolean detectAndReset(JSONArray incomingMessages) {
        if (incomingMessages == null) return false;

        int userMsgCount = 0;
        for (int i = 0; i < incomingMessages.length(); i++) {
            JSONObject m = incomingMessages.optJSONObject(i);
            if (m != null && "user".equals(m.optString("role"))) {
                userMsgCount++;
            }
        }

        // Если пришло только 1 сообщение от пользователя (текущее), а до этого была история — это новый чат
        if (userMsgCount <= 1 && getHistorySize() > 0) {
            clearHistory();
            return true;
        }
        return false;
    }
}