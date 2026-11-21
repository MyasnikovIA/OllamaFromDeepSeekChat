package ru.miacomsoft.config;

import java.io.File;

/**
 * Один профиль Chrome для запуска экземпляра DeepSeek.
 *
 * Теперь здесь только userDataDir: путь к исполняемому файлу Chrome
 * задаётся глобально в Config (chromePathWindows / chromePathLinux),
 * поэтому поле path здесь больше не нужно.
 */
public class BrowserConfig {

    /** Путь к каталогу --user-data-dir. Пустая строка = профиль по умолчанию. */
    public final String userDataDir;

    public BrowserConfig(String userDataDir) {
        this.userDataDir = userDataDir == null ? "" : userDataDir.trim();
    }

    public boolean isValid() {
        return !userDataDir.isEmpty();
    }

    /** Короткое имя для логов: последний компонент пути. */
    public String displayName() {
        if (userDataDir.isEmpty()) return "(default)";
        String n = new File(userDataDir).getName();
        return n.isEmpty() ? userDataDir : n;
    }

    @Override
    public String toString() {
        return "BrowserConfig{userDataDir='" + userDataDir + "'}";
    }
}