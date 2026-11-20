package ru.miacomsoft.bridge;

/**
 * Элемент очереди команд «сервер → браузер».
 * Содержит сериализованный JSON, слушателя стрима и флаги:
 *   fromStdin       — команда пришла из stdin (для печати подсказки "> " после ответа)
 *   isChatCommand   — команда инициирует диалог (send/newChat/…)
 */
public class PendingCommand {

    public final String json;
    public final StreamListener listener;
    public final boolean fromStdin;
    public final boolean isChatCommand;

    public PendingCommand(String json, StreamListener listener, boolean fromStdin, boolean isChatCommand) {
        this.json = json;
        this.listener = listener;
        this.fromStdin = fromStdin;
        this.isChatCommand = isChatCommand;
    }
}