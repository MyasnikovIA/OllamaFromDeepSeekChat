package ru.miacomsoft.ollama;

/**
 * Константы эмуляции Ollama / OpenAI-совместимого API.
 */
public final class OllamaConstants {

    public static final String MODEL_NAME    = "DeepSeekCloud";
    public static final String MODIFIED_AT   = "2024-01-01T00:00:00Z";
    public static final String DIGEST        =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    public static final long   CREATED_EPOCH = 1704067200L;

    /** Флаг отладки: -Dds.debug=true или DS_DEBUG=1 */
    public static final boolean DEBUG = Boolean.parseBoolean(
            System.getProperty("ds.debug",
                    System.getenv().getOrDefault("DS_DEBUG", "false")));

    /** Максимальная длина тела запроса, которую печатаем целиком */
    public static final int LOG_BODY_MAX = 4000;

    private OllamaConstants() {}
}