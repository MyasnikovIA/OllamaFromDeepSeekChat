package ru.miacomsoft.ollama;

import ru.miacomsoft.config.Config;

/**
 * Константы эмуляции Ollama / OpenAI-совместимого API.
 */
public final class OllamaConstants {

    // Не final — значения перезаписываются из Config при старте.
    public static String  MODEL_NAME = "DeepSeekCloud";
    public static boolean DEBUG      = Boolean.parseBoolean(
            System.getProperty("ds.debug",
                    System.getenv().getOrDefault("DS_DEBUG", "false")));

    public static final String MODIFIED_AT   = "2024-01-01T00:00:00Z";
    public static final String DIGEST        =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    public static final long   CREATED_EPOCH = 1704067200L;

    /** Максимальная длина тела запроса, которую печатаем целиком */
    public static final int LOG_BODY_MAX = 4000;

    private OllamaConstants() {}

    /** Применить значения из конфига. Вызывать один раз при старте. */
    public static void applyFromConfig() {
        Config c = Config.get();
        // CLI --debug (system property) имеет приоритет над config.debug
        if (System.getProperty("ds.debug") == null) {
            DEBUG = c.isDebug();
        }
        MODEL_NAME = c.getModelName();
    }
}