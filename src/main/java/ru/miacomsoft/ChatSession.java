package ru.miacomsoft;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChatSession {
    private final List<JSONObject> messageHistory = new ArrayList<>();
    private final Set<String> sentContexts = new HashSet<>();
    private final String systemPrompt = "You are a helpful assistant.";

    public synchronized void clearHistory() {
        messageHistory.clear();
        sentContexts.clear(); // Очищаем кэш контекстов при новом чате
        System.out.println("[SESSION] История сообщений и кэш контекста очищены.");
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

    // --- НОВОЕ: Проверка и маркировка контекста ---
    public synchronized boolean isContextSent(String contextText) {
        return sentContexts.contains(contextText.trim());
    }

    public synchronized void markContextSent(String contextText) {
        sentContexts.add(contextText.trim());
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

        if (userMsgCount <= 1 && getHistorySize() > 0) {
            clearHistory();
            return true;
        }
        return false;
    }
}