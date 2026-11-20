package ru.miacomsoft.bridge;

public class Attachment {
    public final String name;
    public final String mimeType;
    public final String dataBase64;

    public Attachment(String name, String mimeType, String dataBase64) {
        this.name = name;
        this.mimeType = mimeType;
        this.dataBase64 = dataBase64;
    }
}