package ru.miacomsoft.bridge;

import ru.miacomsoft.config.Config;

public final class BridgeConstants {

    // Не final — значения перезаписываются из Config при старте.
    public static long WATCHDOG_TIMEOUT_MS = 120_000L;
    public static long MAX_FILE_BYTES      = 20L * 1024 * 1024;
    public static long MAX_TOTAL_BYTES     = 50L * 1024 * 1024;

    private BridgeConstants() {}

    /** Применить значения из конфига. Вызывать один раз при старте. */
    public static void applyFromConfig() {
        Config c = Config.get();
        WATCHDOG_TIMEOUT_MS = c.getWatchdogTimeoutMs();
        MAX_FILE_BYTES      = c.getMaxFileBytes();
        MAX_TOTAL_BYTES     = c.getMaxTotalBytes();
    }
}