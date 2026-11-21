package ru.miacomsoft.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Загрузчик config.json.
 *
 * Поведение:
 *   1) Если файла нет — создаётся шаблон с комментариями по указанному
 *      пути (создаются и родительские каталоги), затем грузится.
 *   2) Если файл пустой — перезаписывается шаблоном.
 *   3) Перед парсингом из текста удаляются комментарии
 *      (// до конца строки и /* ... *\/), при этом содержимое строковых
 *      литералов ("...") не затрагивается — поэтому "https://..." и
 *      экранированные кавычки \" внутри строк работают корректно.
 *   4) chromePathWindows / chromePathLinux — путь к браузеру.
 *      Пусто (""/null) — авто-поиск Chrome, иначе системный браузер.
 *   5) browsers — массив профилей (--user-data-dir). Каждая запись —
 *      отдельный экземпляр. Допустимы строки либо {"userDataDir": "..."}.
 *   6) hideAddressBar — режим отображения адресной строки у DeepSeek-окон.
 */
public final class Config {

    public static final int DEFAULT_PORT = 11434;

    private static volatile Config INSTANCE = null;

    // ============================================================
    //  Шаблон config.json (создаётся при первом запуске / если файл пуст).
    //  Прямые слеши '/' в путях работают и на Windows, и на Linux.
    // ============================================================
    private static final String DEFAULT_CONFIG_TEMPLATE = """
{
    // HTTP-порт сервера (Ollama API + bridge)
    "port": 11434,

    // Таймаут watchdog: сколько ждём stream-done от браузера (мс)
    "watchdogTimeoutMs": 120000,

    // Максимальный размер одного вложения (байт)
    "maxFileBytes": 20971520,

    // Максимальный суммарный размер всех вложений (байт)
    "maxTotalBytes": 52428800,

    /* Ширина консоли для word-wrap.
       Приоритет: -Dds.console.width > env COLUMNS > это значение. */
    "consoleWidth": 120,

    // Печатать входящие JSON-запросы (или задайте --debug)
    "debug": false,

    // URL, который открывают браузеры (в режиме --app)
    "deepseekUrl": "https://chat.deepseek.com/",

    // Имя модели в Ollama / OpenAI API
    "modelName": "DeepSeekCloud",

    // Папка Chrome-расширения (null = не загружать; --extension= переопределяет)
    "extensionDir": null,

    /* ============================================================
       ПУТЬ К ИСПОЛНЯЕМОМУ ФАЙЛУ БРАУЗЕРА.

       • Если поле для текущей ОС пустое ("") или null — используется
         браузер по умолчанию: сначала авто-поиск Chrome в стандартных
         местах, если не найден — системный браузер (start/open/xdg-open).

       • Если путь указан — используется ТОЛЬКО ОН. Этот же браузер
         применяется и к DeepSeek-профилям (browsers), и к popup-окнам
         почты (mailClientUrl / mailAdminUrl).

       Прямые слеши '/' работают и на Windows — их можно не экранировать.

       Примеры:
         Windows: "C:/ChromeForTesting/chrome-win64/chrome.exe"
                  "C:/Program Files/Google/Chrome/Application/chrome.exe"
         Linux:   "/opt/google/chrome/chrome"
                  "/usr/bin/google-chrome-stable"
       ============================================================ */
    "chromePathWindows": "",
    "chromePathLinux": "",

    /* ============================================================
       СКРЫТЬ АДРЕСНУЮ СТРОКУ (режим --app).

       • false (по умолчанию) — обычное окно Chrome:
         адресная строка, вкладки, меню.
       • true — режим приложения: окно без адресной строки,
         без вкладок и без меню (флаг --app=<url>).

       Применяется и к DeepSeek-профилям (browsers),
       и к одиночному fallback-окну.
       На popup-окна почты (mailClientUrl / mailAdminUrl) НЕ влияет —
       они всегда открываются в обычном режиме.
       ============================================================ */
    "hideAddressBar": false,

    /* ============================================================
       СПИСОК ПРОФИЛЕЙ (--user-data-dir).

       Каждая запись — ОТДЕЛЬНЫЙ запускаемый экземпляр браузера
       (см. chromePathWindows/chromePathLinux) в режиме --app.
       Сколько записей — столько будет запущено окон,
       у каждого свой профиль.

       Если указанный каталог ещё не существует — он будет создан
       автоматически при запуске (вместе со всеми родительскими
       каталогами).

       Допустимы две эквивалентные формы записи:
         "browsers": ["C:/Chrome_Profiles/User1", "C:/Chrome_Profiles/User2"]
         "browsers": [
             {"userDataDir": "C:/Chrome_Profiles/User1"},
             {"userDataDir": "C:/Chrome_Profiles/User2"}
         ]

       Пустой массив [] = открыть ОДИН экземпляр в профиле по умолчанию
       (без --user-data-dir).

       Примеры путей:
         Windows: "C:/Chrome_Profiles/User1"
         Linux:   "/home/user/.chrome-profiles/User1"
       ============================================================ */
    "browsers": [],

    /* Почтовый клиент — открывается ОТДЕЛЬНЫМ окном в ОБЫЧНОМ режиме
       (с адресной строкой, без --app=), в браузере из
       chromePathWindows/chromePathLinux. Использует профиль по умолчанию
       (без --user-data-dir). null или "" = не открывать.
       Например: "https://mail.smwrap.ru/webmail" */
    "mailClientUrl": null,

    /* Админка почты — аналогично (обычный режим, без своего профиля).
       Например: "https://mail.smwrap.ru/admin/login" */
    "mailAdminUrl": null
}
""";

    // -------- поля конфигурации --------
    private int     port              = DEFAULT_PORT;
    private long    watchdogTimeoutMs = 120_000L;
    private long    maxFileBytes      = 20L * 1024 * 1024;
    private long    maxTotalBytes     = 50L * 1024 * 1024;
    private int     consoleWidth      = 120;
    private boolean debug             = false;
    private String  modelName         = "DeepSeekCloud";
    private String  deepseekUrl       = "https://chat.deepseek.com/";
    private String  mailClientUrl     = "";
    private String  mailAdminUrl      = "";
    private String  extensionDir      = "";

    private String  chromePathWindows = "";
    private String  chromePathLinux   = "";
    private boolean hideAddressBar    = false;   // ← НОВОЕ

    private List<BrowserConfig> browsers = Collections.emptyList();

    private Config() {}

    public static Config get() {
        if (INSTANCE == null) {
            synchronized (Config.class) {
                if (INSTANCE == null) INSTANCE = new Config();
            }
        }
        return INSTANCE;
    }

    // ============================================================
    //  Загрузка
    // ============================================================
    public static synchronized void load(File file) throws IOException {
        Config c = new Config();

        if (file == null) {
            synchronized (Config.class) { INSTANCE = c; }
            return;
        }

        boolean created = false;

        if (!file.isFile()) {
            writeDefaultConfig(file);
            created = true;
        }

        String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        if (raw.isBlank()) {
            writeDefaultConfig(file);
            raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            created = true;
        }

        if (created) {
            System.out.println("[CONFIG] created default config: " + file.getAbsolutePath());
        }

        // Удаляем комментарии — org.json их не понимает.
        String json = stripJsonComments(raw);

        JSONObject obj;
        try {
            obj = new JSONObject(json);
        } catch (JSONException e) {
            printJsonError(json, e);
            throw e;
        }
        c.parseFromJson(obj);
        synchronized (Config.class) { INSTANCE = c; }
    }

    /**
     * Удаляет из текста JSON-комментарии:
     *   // ... до конца строки
     *   /* ... *\/
     * При этом строковые литералы ("...") обрабатываются как атомарные
     * (с учётом экранирования \"), поэтому "https://..." и
     * "/* not a comment *\/" внутри строк не трогаются.
     *
     * Символы перевода строки сохраняются, чтобы номера строк в
     * сообщениях об ошибках оставались осмысленными.
     */
    static String stripJsonComments(String src) {
        if (src == null || src.isEmpty()) return src;

        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        boolean inString = false;

        while (i < n) {
            char c = src.charAt(i);

            // Внутри строкового литерала — копируем всё как есть,
            // учитывая экранированные символы (\", \\, \n и т.п.).
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(src.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') inString = false;
                i++;
                continue;
            }

            // Начало строкового литерала
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }

            // Комментарии
            if (c == '/' && i + 1 < n) {
                char c2 = src.charAt(i + 1);

                // // — до конца строки (перевод строки оставляем)
                if (c2 == '/') {
                    i += 2;
                    while (i < n && src.charAt(i) != '\n') i++;
                    continue;
                }

                // /* ... */ — блок; переводы строк внутри сохраняем
                if (c2 == '*') {
                    i += 2;
                    while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                        if (src.charAt(i) == '\n') out.append('\n');
                        i++;
                    }
                    i = Math.min(i + 2, n);
                    continue;
                }
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static void printJsonError(String json, JSONException e) {
        int line = 1, col = 1;
        String msg = String.valueOf(e.getMessage());
        // org.json обычно даёт позицию в сообщении — попробуем вытащить
        try {
            int at = msg.indexOf(" at ");
            if (at > 0) {
                int pos = Integer.parseInt(msg.substring(at + 4).replaceAll("[^0-9].*$", ""));
                for (int k = 0; k < Math.min(pos, json.length()); k++) {
                    if (json.charAt(k) == '\n') { line++; col = 1; }
                    else col++;
                }
            }
        } catch (Exception ignored) {}
        System.err.println("[CONFIG] JSON parse error at line " + line + ", col " + col
                + ": " + e.getMessage());
    }

    private static void writeDefaultConfig(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Files.write(file.toPath(),
                DEFAULT_CONFIG_TEMPLATE.getBytes(StandardCharsets.UTF_8));
    }

    // ============================================================
    //  Парсинг
    // ============================================================
    private void parseFromJson(JSONObject obj) {
        this.port              = obj.optInt("port", DEFAULT_PORT);
        this.watchdogTimeoutMs = obj.optLong("watchdogTimeoutMs", 120_000L);
        this.maxFileBytes      = obj.optLong("maxFileBytes", 20L * 1024 * 1024);
        this.maxTotalBytes     = obj.optLong("maxTotalBytes", 50L * 1024 * 1024);
        this.consoleWidth      = obj.optInt("consoleWidth", 120);
        this.debug             = obj.optBoolean("debug", false);
        this.modelName         = obj.optString("modelName", "DeepSeekCloud");
        this.deepseekUrl       = obj.optString("deepseekUrl", "https://chat.deepseek.com/");
        this.mailClientUrl     = obj.optString("mailClientUrl", "");
        this.mailAdminUrl      = obj.optString("mailAdminUrl", "");

        // extensionDir: null или строка
        if (obj.isNull("extensionDir") || !obj.has("extensionDir")) {
            this.extensionDir = "";
        } else {
            this.extensionDir = obj.optString("extensionDir", "");
        }

        this.chromePathWindows = obj.optString("chromePathWindows", "");
        this.chromePathLinux   = obj.optString("chromePathLinux",   "");
        this.hideAddressBar    = obj.optBoolean("hideAddressBar", false);   // ← НОВОЕ

        // browsers: массив строк или объектов {"userDataDir": "..."}
        List<BrowserConfig> list = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("browsers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                if (o == null) continue;
                if (o instanceof String) {
                    String s = ((String) o).trim();
                    if (!s.isEmpty()) list.add(new BrowserConfig(s));
                } else if (o instanceof JSONObject) {
                    JSONObject b = (JSONObject) o;
                    String dir = b.optString("userDataDir", "").trim();
                    if (!dir.isEmpty()) list.add(new BrowserConfig(dir));
                }
            }
        }
        this.browsers = list.isEmpty() ? Collections.emptyList() : list;
    }

    // ============================================================
    //  Геттеры
    // ============================================================
    public int     getPort()              { return port; }
    public long    getWatchdogTimeoutMs() { return watchdogTimeoutMs; }
    public long    getMaxFileBytes()      { return maxFileBytes; }
    public long    getMaxTotalBytes()     { return maxTotalBytes; }
    public int     getConsoleWidth()      { return consoleWidth; }
    public boolean isDebug()              { return debug; }
    public String  getModelName()         { return modelName; }
    public String  getDeepseekUrl()       { return deepseekUrl; }
    public String  getMailClientUrl()     { return mailClientUrl; }
    public String  getMailAdminUrl()      { return mailAdminUrl; }
    public String  getExtensionDir()      { return extensionDir; }

    public String  getChromePathWindows() { return chromePathWindows; }
    public String  getChromePathLinux()   { return chromePathLinux; }
    public boolean isHideAddressBar()     { return hideAddressBar; }   // ← НОВОЕ

    /** Путь к браузеру для текущей ОС (Windows/Linux). */
    public String getChromePath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return chromePathWindows;
        if (os.contains("nux") || os.contains("nix") || os.contains("aix"))
            return chromePathLinux;
        // macOS (и прочие) — используем windows-путь как безопасный дефолт?
        // Реально ChromeLauncher сам делает auto-detect, если путь пуст.
        return "";
    }

    public List<BrowserConfig> getBrowsers() { return browsers; }
}