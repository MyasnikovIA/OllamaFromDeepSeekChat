package ru.miacomsoft.config;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private final String  profileDir;
    private final String  deepseekUrl;
    private final String  modelName;

    private Config(int port,
                   long watchdogTimeoutMs,
                   long maxFileBytes,
                   long maxTotalBytes,
                   int consoleWidth,
                   boolean debug,
                   String extensionDir,
                   String profileDir,
                   String deepseekUrl,
                   String modelName) {
        this.port              = port;
        this.watchdogTimeoutMs = watchdogTimeoutMs;
        this.maxFileBytes      = maxFileBytes;
        this.maxTotalBytes     = maxTotalBytes;
        this.consoleWidth      = consoleWidth;
        this.debug             = debug;
        this.extensionDir      = extensionDir;
        this.profileDir        = profileDir;
        this.deepseekUrl       = deepseekUrl;
        this.modelName         = modelName;
    }

    // ---- getters ----
    public int     getPort()              { return port; }
    public long    getWatchdogTimeoutMs() { return watchdogTimeoutMs; }
    public long    getMaxFileBytes()      { return maxFileBytes; }
    public long    getMaxTotalBytes()     { return maxTotalBytes; }
    public int     getConsoleWidth()      { return consoleWidth; }
    public boolean isDebug()              { return debug; }
    public String  getExtensionDir()      { return extensionDir; }
    public String  getProfileDir()        { return profileDir; }
    public String  getDeepseekUrl()       { return deepseekUrl; }
    public String  getModelName()         { return modelName; }

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
                null,
                DEFAULT_DEEPSEEK_URL,
                DEFAULT_MODEL_NAME);
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
                + ", debug=" + cfg.debug + ")");
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
        String  profileDir = json.isNull("profileDir")   ? null : json.optString("profileDir", null);
        String  url        = json.optString("deepseekUrl", DEFAULT_DEEPSEEK_URL);
        String  model      = json.optString("modelName", DEFAULT_MODEL_NAME);

        if (port <= 0 || port > 65535) port = DEFAULT_PORT;
        if (width < 40) width = DEFAULT_CONSOLE_WIDTH;
        if (watchdog <= 0) watchdog = DEFAULT_WATCHDOG_TIMEOUT_MS;
        if (maxFile  <= 0) maxFile  = DEFAULT_MAX_FILE_BYTES;
        if (maxTotal <= 0) maxTotal = DEFAULT_MAX_TOTAL_BYTES;
        if (url   == null || url.isEmpty())   url   = DEFAULT_DEEPSEEK_URL;
        if (model == null || model.isEmpty()) model = DEFAULT_MODEL_NAME;

        return new Config(port, watchdog, maxFile, maxTotal, width, debug,
                extDir, profileDir, url, model);
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

            // // line comment
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') { out.append(' '); i++; }
                continue;
            }
            // # line comment
            if (c == '#') {
                while (i < n && src.charAt(i) != '\n') { out.append(' '); i++; }
                continue;
            }
            // /* block comment */
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
        return
                "{\n" +
                        "    // HTTP-порт сервера (Ollama API + bridge)\n" +
                        "    \"port\": 11434,\n" +
                        "\n" +
                        "    // Таймаут watchdog: сколько ждём stream-done от браузера (мс)\n" +
                        "    \"watchdogTimeoutMs\": 120000,\n" +
                        "\n" +
                        "    // Максимальный размер одного вложения (байт)\n" +
                        "    \"maxFileBytes\": 20971520,\n" +
                        "\n" +
                        "    // Максимальный суммарный размер всех вложений (байт)\n" +
                        "    \"maxTotalBytes\": 52428800,\n" +
                        "\n" +
                        "    /* Ширина консоли для word-wrap.\n" +
                        "       Приоритет: -Dds.console.width > env COLUMNS > это значение. */\n" +
                        "    \"consoleWidth\": 120,\n" +
                        "\n" +
                        "    // Печатать входящие JSON-запросы (или задайте --debug)\n" +
                        "    \"debug\": false,\n" +
                        "\n" +
                        "    // Путь к папке Chrome-расширения (null = не загружать)\n" +
                        "    \"extensionDir\": null,\n" +
                        "\n" +
                        "    // Путь к профилю Chrome (null = по умолчанию)\n" +
                        "    \"profileDir\": null,\n" +
                        "\n" +
                        "    // URL, который открывает ChromeLauncher\n" +
                        "    \"deepseekUrl\": \"https://chat.deepseek.com/\",\n" +
                        "\n" +
                        "    // Имя модели в Ollama / OpenAI API\n" +
                        "    \"modelName\": \"DeepSeekCloud\"\n" +
                        "}\n";
    }
}