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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Единая точка чтения настроек приложения.
 *
 * Настройки хранятся в JSON-файле config.json (путь можно задать
 * через --config=<path> или --config <path>). Допускаются комментарии:
 *   // однострочный
 *   #  однострочный
 *   /* многострочный *\/
 *
 * Если файла нет — он создаётся с настройками по умолчанию.
 * Если JSON битый — печатается проблемная строка с комментарием,
 * и работа приложения завершается с кодом 1.
 */
public final class Config {

    // ---- defaults ----
    public static final int     DEFAULT_PORT                = 11434;
    public static final long    DEFAULT_WATCHDOG_TIMEOUT_MS = 120_000L;
    public static final long    DEFAULT_MAX_FILE_BYTES      = 20L * 1024 * 1024;
    public static final long    DEFAULT_MAX_TOTAL_BYTES     = 50L * 1024 * 1024;
    public static final int     DEFAULT_CONSOLE_WIDTH       = 120;
    public static final boolean DEFAULT_DEBUG               = false;
    public static final String  DEFAULT_DEEPSEEK_URL        = "https://chat.deepseek.com/";
    public static final String  DEFAULT_MODEL_NAME          = "DeepSeekCloud";

    private static volatile Config INSTANCE;

    private final int     port;
    private final long    watchdogTimeoutMs;
    private final long    maxFileBytes;
    private final long    maxTotalBytes;
    private final int     consoleWidth;
    private final boolean debug;
    private final String  extensionDir;
    private final String  deepseekUrl;
    private final String  modelName;
    private final List<BrowserConfig> browsers;
    private final String  mailClientUrl;
    private final String  mailAdminUrl;

    private Config(int port,
                   long watchdogTimeoutMs,
                   long maxFileBytes,
                   long maxTotalBytes,
                   int consoleWidth,
                   boolean debug,
                   String extensionDir,
                   String deepseekUrl,
                   String modelName,
                   List<BrowserConfig> browsers,
                   String mailClientUrl,
                   String mailAdminUrl) {
        this.port              = port;
        this.watchdogTimeoutMs = watchdogTimeoutMs;
        this.maxFileBytes      = maxFileBytes;
        this.maxTotalBytes     = maxTotalBytes;
        this.consoleWidth      = consoleWidth;
        this.debug             = debug;
        this.extensionDir      = extensionDir;
        this.deepseekUrl       = deepseekUrl;
        this.modelName         = modelName;
        this.browsers          = Collections.unmodifiableList(browsers);
        this.mailClientUrl     = mailClientUrl;
        this.mailAdminUrl      = mailAdminUrl;
    }

    // ---- getters ----
    public int     getPort()              { return port; }
    public long    getWatchdogTimeoutMs() { return watchdogTimeoutMs; }
    public long    getMaxFileBytes()      { return maxFileBytes; }
    public long    getMaxTotalBytes()     { return maxTotalBytes; }
    public int     getConsoleWidth()      { return consoleWidth; }
    public boolean isDebug()              { return debug; }
    public String  getExtensionDir()      { return extensionDir; }
    public String  getDeepseekUrl()       { return deepseekUrl; }
    public String  getModelName()         { return modelName; }
    public List<BrowserConfig> getBrowsers() { return browsers; }
    public String  getMailClientUrl()     { return mailClientUrl; }
    public String  getMailAdminUrl()      { return mailAdminUrl; }

    // ---- singleton ----
    public static Config get() {
        Config c = INSTANCE;
        if (c == null) {
            synchronized (Config.class) {
                c = INSTANCE;
                if (c == null) {
                    c = createDefaults();
                    INSTANCE = c;
                }
            }
        }
        return c;
    }

    private static Config createDefaults() {
        return new Config(
                DEFAULT_PORT,
                DEFAULT_WATCHDOG_TIMEOUT_MS,
                DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_TOTAL_BYTES,
                DEFAULT_CONSOLE_WIDTH,
                DEFAULT_DEBUG,
                null,
                DEFAULT_DEEPSEEK_URL,
                DEFAULT_MODEL_NAME,
                new ArrayList<>(),
                null,
                null);
    }

    // ============================================================
    //  Loading
    // ============================================================
    public static Config load(File file) throws IOException, JSONException {
        if (file == null) file = new File("config.json");

        if (!file.exists()) {
            Files.writeString(file.toPath(), defaultJsonText(), StandardCharsets.UTF_8);
            System.out.println("[CONFIG] created default config: " + file.getAbsolutePath());
        }

        String original = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        String stripped = stripComments(original);

        JSONObject json;
        try {
            json = new JSONObject(stripped);
        } catch (JSONException e) {
            printJsonError(original, stripped, e);
            throw e;
        }

        Config cfg = fromJson(json);
        INSTANCE = cfg;
        System.out.println("[CONFIG] loaded: " + file.getAbsolutePath()
                + " (port=" + cfg.port
                + ", model=" + cfg.modelName
                + ", debug=" + cfg.debug
                + ", browsers=" + cfg.browsers.size() + ")");
        return cfg;
    }

    private static Config fromJson(JSONObject json) {
        int     port       = json.optInt("port", DEFAULT_PORT);
        long    watchdog   = json.optLong("watchdogTimeoutMs", DEFAULT_WATCHDOG_TIMEOUT_MS);
        long    maxFile    = json.optLong("maxFileBytes", DEFAULT_MAX_FILE_BYTES);
        long    maxTotal   = json.optLong("maxTotalBytes", DEFAULT_MAX_TOTAL_BYTES);
        int     width      = json.optInt("consoleWidth", DEFAULT_CONSOLE_WIDTH);
        boolean debug      = json.optBoolean("debug", DEFAULT_DEBUG);
        String  extDir     = json.isNull("extensionDir") ? null : json.optString("extensionDir", null);
        String  url        = json.optString("deepseekUrl", DEFAULT_DEEPSEEK_URL);
        String  model      = json.optString("modelName", DEFAULT_MODEL_NAME);
        String  mailClient = json.isNull("mailClientUrl") ? null : json.optString("mailClientUrl", null);
        String  mailAdmin  = json.isNull("mailAdminUrl")  ? null : json.optString("mailAdminUrl",  null);

        if (port <= 0 || port > 65535) port = DEFAULT_PORT;
        if (width < 40) width = DEFAULT_CONSOLE_WIDTH;
        if (watchdog <= 0) watchdog = DEFAULT_WATCHDOG_TIMEOUT_MS;
        if (maxFile  <= 0) maxFile  = DEFAULT_MAX_FILE_BYTES;
        if (maxTotal <= 0) maxTotal = DEFAULT_MAX_TOTAL_BYTES;
        if (url   == null || url.isEmpty())   url   = DEFAULT_DEEPSEEK_URL;
        if (model == null || model.isEmpty()) model = DEFAULT_MODEL_NAME;

        // browsers[]
        List<BrowserConfig> browsers = new ArrayList<>();
        JSONArray arr = json.optJSONArray("browsers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                String name = b.optString("name", "browser-" + (i + 1));
                String path = b.isNull("path")        ? null : b.optString("path", null);
                String udd  = b.isNull("userDataDir") ? null : b.optString("userDataDir", null);
                if (path == null || path.isEmpty()) {
                    System.err.println("[CONFIG] browsers[" + i + "] has no 'path', skipped");
                    continue;
                }
                browsers.add(new BrowserConfig(name, path, udd));
            }
        }

        return new Config(port, watchdog, maxFile, maxTotal, width, debug,
                extDir, url, model, browsers, mailClient, mailAdmin);
    }

    // ============================================================
    //  Comment stripping
    //
    //  Каждый символ комментария заменяется пробелом, переводы строк
    //  сохраняются — позиции (line/col) не сдвигаются, поэтому
    //  ошибки org.json напрямую проецируются на оригинальный текст.
    // ============================================================
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        boolean inString = false;

        while (i < n) {
            char c = src.charAt(i);

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

            if (c == '"') { inString = true; out.append(c); i++; continue; }

            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') { out.append(' '); i++; }
                continue;
            }
            if (c == '#') {
                while (i < n && src.charAt(i) != '\n') { out.append(' '); i++; }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                out.append(' ').append(' ');
                i += 2;
                while (i < n) {
                    if (i + 1 < n && src.charAt(i) == '*' && src.charAt(i + 1) == '/') {
                        out.append(' ').append(' ');
                        i += 2;
                        break;
                    }
                    char cc = src.charAt(i);
                    out.append(cc == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // ============================================================
    //  Error highlighting
    // ============================================================
    private static final Pattern LINE_PATTERN = Pattern.compile("line (\\d+)");
    private static final Pattern AT_PATTERN   = Pattern.compile("at (\\d+)");

    static void printJsonError(String original, String stripped, JSONException e) {
        String msg = (e.getMessage() == null) ? "unknown error" : e.getMessage();

        int line   = -1;
        int offset = -1;
        Matcher lm = LINE_PATTERN.matcher(msg);
        if (lm.find()) line = Integer.parseInt(lm.group(1)) - 1;
        Matcher om = AT_PATTERN.matcher(msg);
        if (om.find()) offset = Integer.parseInt(om.group(1));

        String[] lines = original.split("\n", -1);

        if (line < 0 && offset >= 0) {
            int pos = 0;
            for (int i = 0; i < lines.length; i++) {
                if (pos + lines[i].length() >= offset) { line = i; break; }
                pos += lines[i].length() + 1;
            }
        }
        if (line < 0) line = 0;

        int start = Math.max(0, line - 3);
        int end   = Math.min(lines.length, line + 4);

        System.err.println();
        System.err.println("[CONFIG] ========== JSON parse error ==========");
        System.err.println("[CONFIG] " + msg);
        System.err.println("[CONFIG] around line " + (line + 1) + ":");
        System.err.println("[CONFIG] ----------------------------------------");
        for (int i = start; i < end; i++) {
            String marker = (i == line) ? " >>> " : "     ";
            System.err.println(marker + String.format("%3d", i + 1) + " | " + lines[i]);
        }
        System.err.println("[CONFIG] ----------------------------------------");
        System.err.println("[CONFIG] Fix the highlighted line and restart.");
        System.err.println("[CONFIG] ========================================");
        System.err.println();
    }

    // ============================================================
    //  Default config template
    // ============================================================
    private static String defaultJsonText() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("    // HTTP-порт сервера (Ollama API + bridge)\n");
        sb.append("    \"port\": 11434,\n");
        sb.append("\n");
        sb.append("    // Таймаут watchdog: сколько ждём stream-done от браузера (мс)\n");
        sb.append("    \"watchdogTimeoutMs\": 120000,\n");
        sb.append("\n");
        sb.append("    // Максимальный размер одного вложения (байт)\n");
        sb.append("    \"maxFileBytes\": 20971520,\n");
        sb.append("\n");
        sb.append("    // Максимальный суммарный размер всех вложений (байт)\n");
        sb.append("    \"maxTotalBytes\": 52428800,\n");
        sb.append("\n");
        sb.append("    /* Ширина консоли для word-wrap.\n");
        sb.append("       Приоритет: -Dds.console.width > env COLUMNS > это значение. */\n");
        sb.append("    \"consoleWidth\": 120,\n");
        sb.append("\n");
        sb.append("    // Печатать входящие JSON-запросы (или задайте --debug)\n");
        sb.append("    \"debug\": false,\n");
        sb.append("\n");
        sb.append("    // URL, который открывают браузеры (в режиме --app)\n");
        sb.append("    \"deepseekUrl\": \"https://chat.deepseek.com/\",\n");
        sb.append("\n");
        sb.append("    // Имя модели в Ollama / OpenAI API\n");
        sb.append("    \"modelName\": \"DeepSeekCloud\",\n");
        sb.append("\n");
        sb.append("    // Папка Chrome-расширения (null = не загружать; --extension= переопределяет)\n");
        sb.append("    \"extensionDir\": null,\n");
        sb.append("\n");
        sb.append("    /* ============================================================\n");
        sb.append("       Список браузеров для автоматического запуска.\n");
        sb.append("       Каждый браузер открывается на deepseekUrl в режиме --app\n");
        sb.append("       (без адресной строки и вкладок) со своим профилем\n");
        sb.append("       --user-data-dir. Пустой массив = авто-поиск Chrome.\n");
        sb.append("\n");
        sb.append("       Внимание: прямые слеши '/' работают и на Windows\n");
        sb.append("       (Chrome и java.io.File их понимают).\n");
        sb.append("\n");
        sb.append("       Пример Windows:\n");
        sb.append("         {\"name\":\"User1\",\n");
        sb.append("          \"path\":\"C:/Program Files/Google/Chrome/Application/chrome.exe\",\n");
        sb.append("          \"userDataDir\":\"C:/Chrome_Profiles/User1\"}\n");
        sb.append("\n");
        sb.append("       Пример Linux:\n");
        sb.append("         {\"name\":\"User1\",\n");
        sb.append("          \"path\":\"/usr/bin/google-chrome\",\n");
        sb.append("          \"userDataDir\":\"/home/user/.chrome-profiles/User1\"}\n");
        sb.append("\n");
        sb.append("       Пример macOS:\n");
        sb.append("         {\"name\":\"User1\",\n");
        sb.append("          \"path\":\"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome\",\n");
        sb.append("          \"userDataDir\":\"/Users/user/.chrome-profiles/User1\"}\n");
        sb.append("       ============================================================ */\n");
        sb.append("    \"browsers\": [],\n");
        sb.append("\n");
        sb.append("    /* Почтовый клиент — открывается отдельным --app окном\n");
        sb.append("       без своего --user-data-dir. null или \"\" = не открывать.\n");
        sb.append("       Например: \"https://mail.smwrap.ru/webmail\" */\n");
        sb.append("    \"mailClientUrl\": null,\n");
        sb.append("\n");
        sb.append("    /* Админка почты — аналогично (без своего профиля).\n");
        sb.append("       Например: \"https://mail.smwrap.ru/admin/login\" */\n");
        sb.append("    \"mailAdminUrl\": null\n");
        sb.append("}\n");
        return sb.toString();
    }
}