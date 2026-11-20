package ru.miacomsoft.bridge;

public interface StreamListener {
    void onChunk(String text, String type);
    void onDone();
    void onError(String message);
}