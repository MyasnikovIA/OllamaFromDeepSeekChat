package ru.miacomsoft.config;

/**
 * Один профиль браузера из массива "browsers" в config.json.
 *
 *   name        — человекочитаемое имя (для логов)
 *   path        — путь к исполняемому файлу Chrome/Chromium
 *   userDataDir — каталог для --user-data-dir (изолированный профиль)
 */
public final class BrowserConfig {

    public final String name;
    public final String path;
    public final String userDataDir;

    public BrowserConfig(String name, String path, String userDataDir) {
        this.name        = (name == null || name.isEmpty()) ? "browser" : name;
        this.path        = path;
        this.userDataDir = userDataDir;
    }

    public boolean isValid() {
        return path != null && !path.isEmpty();
    }

    @Override
    public String toString() {
        return name + " (" + path + " → " + userDataDir + ")";
    }
}