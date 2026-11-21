package ru.miacomsoft;

import com.sun.net.httpserver.HttpServer;
import ru.miacomsoft.bridge.BridgeCommands;
import ru.miacomsoft.bridge.BridgeConstants;
import ru.miacomsoft.bridge.BridgeRouter;
import ru.miacomsoft.bridge.ConsoleWriter;
import ru.miacomsoft.config.BrowserConfig;
import ru.miacomsoft.config.Config;
import ru.miacomsoft.ollama.OllamaConstants;
import ru.miacomsoft.ollama.OllamaRouter;
import ru.miacomsoft.session.SessionManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Точка входа.
 *
 * Аргументы командной строки (args):
 *   --no-browser              не открывать Chrome
 *   --debug                   включить отладочный вывод JSON
 *   --extension=<путь>        путь к папке расширения Chrome
 *   --profile=<путь>          путь к профилю Chrome
 *   --config=<путь>           путь к config.json (по умолчанию — ./config.json)
 *   --config <путь>           то же самое, но путь отдельным аргументом
 */
public class Main {

    public static int    PORT       = Config.DEFAULT_PORT;
    public static File   CONFIG_FILE = new File("config.json");

    public static void main(String[] args) throws Exception {
        boolean noBrowser = false;
        String  configPath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--no-browser".equals(arg)) {
                noBrowser = true;
            } else if ("--debug".equals(arg)) {
                System.setProperty("ds.debug", "true");
            } else if (arg.startsWith("--extension=")) {
                System.setProperty("ds.extension.dir", arg.substring("--extension=".length()));
            } else if (arg.startsWith("--profile=")) {
                System.setProperty("ds.profile.dir", arg.substring("--profile=".length()));
            } else if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length());
            } else if ("--config".equals(arg) && i + 1 < args.length) {
                configPath = args[++i];
            }
        }

        CONFIG_FILE = (configPath == null || configPath.isEmpty())
                ? new File("config.json")
                : new File(configPath);

        try {
            Config.load(CONFIG_FILE);
        } catch (org.json.JSONException e) {
            System.err.println("[CONFIG] JSON parse failed. Exiting.");
            System.exit(1);
            return;
        } catch (java.io.IOException e) {
            System.err.println("[CONFIG] read failed: " + e.getMessage() + " — using defaults.");
        }

        BridgeConstants.applyFromConfig();
        OllamaConstants.applyFromConfig();
        ConsoleWriter.get().reconfigure();

        PORT = Config.get().getPort();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        BridgeRouter.register(server);
        OllamaRouter.register(server);
        server.start();

        printBanner();

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        sched.scheduleAtFixedRate(BridgeCommands::watchdogTick, 10, 10, TimeUnit.SECONDS);

        Config cfg = Config.get();
        if (!noBrowser && cfg.isAutoLaunchBrowsers()) {
            ChromeLauncher.openDeepSeek();
        } else {
            System.out.println("[CHROME] Автозапуск браузеров отключён (или передан флаг --no-browser).");
            System.out.println("[CHROME] Используйте /browsers для списка и /openbrowser <n> для запуска.");
        }

        Thread stdinThread = new Thread(() -> {
            try {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) {
                        System.out.print("> ");
                        System.out.flush();
                        continue;
                    }
                    handleStdin(line);
                }
            } catch (Exception e) {
                System.out.println("\n[STDIN] error: " + e.getMessage());
            }
        });
        stdinThread.setDaemon(true);
        stdinThread.start();
    }

    static void printBanner() {
        System.out.println("========================================================");
        System.out.println("[SERVER] listening on port " + PORT);
        System.out.println("[SERVER] config file : " + CONFIG_FILE.getAbsolutePath());
        System.out.println("[SERVER] Ollama API : http://localhost:" + PORT + "/api/tags");
        System.out.println("[SERVER] Bridge SSE : http://localhost:" + PORT + "/events?tabId=...");
        System.out.println("[SERVER] Bridge log : http://localhost:" + PORT + "/log");
        System.out.println("[SERVER] Tabs list  : http://localhost:" + PORT + "/tabs");
        System.out.println("[SERVER] debug JSON : " + (OllamaConstants.DEBUG ? "ON" : "off"));
        System.out.println();
        System.out.println("  <текст>              → sendToDeepSeek(text)");
        System.out.println("  ! <js>               → eval в консоли страницы");
        System.out.println("  /new                 → создать новый чат");
        System.out.println("  /think on            → включить режим размышления");
        System.out.println("  /think off           → выключить режим размышления");
        System.out.println("  /search on           → включить умный поиск");
        System.out.println("  /search off          → выключить умный поиск");
        System.out.println("  /stop                → остановить текущую генерацию");
        System.out.println("  /resume              → продолжить остановленную генерацию");
        System.out.println("  /file <path> [text]  → отправить файл в DeepSeek");
        System.out.println("  /clipattach <path>   → прикрепить файл через буфер обмена (для больших текстов)");
        System.out.println("  /clipcopy   <path>   → скопировать файл в буфер обмена (без вставки)");
        System.out.println("  /clippaste           → вставить содержимое буфера в поле ввода (алиас: /cp)");
        System.out.println("  /tabs                → список открытых вкладок DeepSeek");
        System.out.println("  /tab <n|id>          → выбрать вкладку");
        System.out.println("  /opentab [url]       → открыть новую вкладку DeepSeek");
        System.out.println("  /newtab [url]        → алиас для /opentab");
        System.out.println("  /close <n|id>        → закрыть вкладку");
        System.out.println("  /closeall            → закрыть ВСЕ вкладки");
        System.out.println("  /state               → показать режимы активной вкладки");
        System.out.println("  /browsers            → список доступных профилей браузера");
        System.out.println("  /openbrowser <n>     → запустить браузер по индексу из списка");
        System.out.println("  /help                → эта подсказка");
        System.out.println("========================================================");
        System.out.print("> ");
        System.out.flush();
    }

    static void handleStdin(String line) {
        String trimmed = line.trim();

        if (trimmed.equals("/help") || trimmed.equals("/?")) {
            printBanner();
            return;
        }

        if (trimmed.equals("/new") || trimmed.equals("/newchat")) {
            SessionManager.clearHistory();
            BridgeCommands.sendNewChatFromStdin();
            return;
        }

        if (trimmed.equals("/think on") || trimmed.equals("/think")) {
            BridgeCommands.sendEnableThinkingFromStdin();
            return;
        }
        if (trimmed.equals("/think off")) {
            BridgeCommands.sendDisableThinkingFromStdin();
            return;
        }
        if (trimmed.equals("/search on") || trimmed.equals("/search")) {
            BridgeCommands.sendEnableSmartSearchFromStdin();
            return;
        }
        if (trimmed.equals("/search off")) {
            BridgeCommands.sendDisableSmartSearchFromStdin();
            return;
        }

        if (trimmed.equals("/stop")) {
            BridgeCommands.sendStopFromStdin();
            return;
        }
        if (trimmed.equals("/resume") || trimmed.equals("/continue")) {
            BridgeCommands.sendResumeFromStdin();
            return;
        }

        if (trimmed.equals("/browsers")) {
            List<BrowserConfig> list = Config.get().getBrowsers();
            ConsoleWriter.get().ensureNewline();
            System.out.println("[BROWSERS] count: " + (list == null ? 0 : list.size()));
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    BrowserConfig bc = list.get(i);
                    System.out.println("  #" + i + " " + bc.displayName() + " (" + bc.userDataDir + ")");
                }
            }
            System.out.print("> "); System.out.flush();
            return;
        }

        if (trimmed.startsWith("/openbrowser ")) {
            String arg = trimmed.substring("/openbrowser ".length()).trim();
            try {
                int idx = Integer.parseInt(arg);
                ChromeLauncher.launchBrowserAtIndex(idx);
            } catch (NumberFormatException e) {
                ConsoleWriter.get().ensureNewline();
                System.out.println("[!] invalid index: " + arg);
                System.out.print("> "); System.out.flush();
            }
            return;
        }

        // ============================================================
        //  /file <path> [text]
        // ============================================================
        if (trimmed.startsWith("/file ")) {
            String rest = trimmed.substring("/file ".length()).trim();
            if (rest.isEmpty()) {
                System.out.println("[FILE] usage: /file <path> [text]");
                System.out.print("> "); System.out.flush();
                return;
            }
            String[] pt = parsePathAndRest(rest);
            String path = pt[0];
            String text = pt[1];

            SessionManager.addMessage("user",
                    "Отправлен файл: " + path + (text.isEmpty() ? "" : " | Текст: " + text));
            BridgeCommands.sendFileFromStdin(path, text);
            return;
        }

        // ============================================================
        //  /clipattach <path>  — прикрепить через буфер обмена
        // ============================================================
        if (trimmed.startsWith("/clipattach ")) {
            String rest = trimmed.substring("/clipattach ".length()).trim();
            if (rest.isEmpty()) {
                System.out.println("[CLIPATTACH] usage: /clipattach <path>");
                System.out.print("> "); System.out.flush();
                return;
            }
            String[] pt = parsePathAndRest(rest);
            String path = pt[0];

            SessionManager.addMessage("user", "Clipboard-attach: " + path);
            BridgeCommands.sendClipboardAttachFromStdin(path);
            return;
        }

        // ============================================================
        //  /clipcopy <path>  — только скопировать в буфер обмена
        // ============================================================
        if (trimmed.startsWith("/clipcopy ")) {
            String rest = trimmed.substring("/clipcopy ".length()).trim();
            if (rest.isEmpty()) {
                System.out.println("[CLIPCOPY] usage: /clipcopy <path>");
                System.out.print("> "); System.out.flush();
                return;
            }
            String[] pt = parsePathAndRest(rest);
            String path = pt[0];

            BridgeCommands.sendClipboardCopyFromStdin(path);
            return;
        }

        // ============================================================
        //  /clippaste — вставить из буфера в composer
        //  (алиас: /cp)
        // ============================================================
        if (trimmed.equals("/clippaste") || trimmed.equals("/cp")) {
            BridgeCommands.sendClipboardPasteFromStdin();
            return;
        }

        if (trimmed.equals("/tabs")) {
            BridgeCommands.printTabs();
            return;
        }

        if (trimmed.equals("/state")) {
            BridgeCommands.printState();
            return;
        }

        if (trimmed.equals("/opentab")
                || trimmed.equals("/newtab")
                || trimmed.startsWith("/opentab ")
                || trimmed.startsWith("/newtab ")) {
            String arg = "";
            int sp = trimmed.indexOf(' ');
            if (sp >= 0) arg = trimmed.substring(sp + 1).trim();
            BridgeCommands.openNewTabFromStdin(arg);
            return;
        }

        if (trimmed.equals("/closeall")) {
            BridgeCommands.closeAllTabsFromStdin();
            return;
        }

        if (trimmed.startsWith("/close ")) {
            String arg = trimmed.substring("/close ".length()).trim();
            if (arg.isEmpty()) {
                System.out.println("[TAB] usage: /close <n|id>");
                System.out.print("> "); System.out.flush();
                return;
            }
            String targetId = null;
            try {
                int idx = Integer.parseInt(arg);
                targetId = BridgeCommands.getTabIdByIndex(idx);
            } catch (NumberFormatException e) {
                targetId = BridgeCommands.findActiveTabId(arg);
            }
            if (targetId == null) {
                System.out.println("[TAB] not found: " + arg);
                System.out.print("> "); System.out.flush();
                return;
            }
            System.out.println("[TAB] closing: " + targetId);
            BridgeCommands.closeTabFromStdin(targetId);
            return;
        }

        if (trimmed.startsWith("/tab ")) {
            String arg = trimmed.substring("/tab ".length()).trim();
            if (arg.isEmpty()) {
                System.out.println("[TAB] usage: /tab <n|id>");
                System.out.print("> "); System.out.flush();
                return;
            }
            boolean ok;
            try {
                int idx = Integer.parseInt(arg);
                ok = BridgeCommands.selectTabByIndex(idx);
            } catch (NumberFormatException e) {
                ok = BridgeCommands.selectTabById(arg)
                        || BridgeCommands.selectTabByPrefix(arg);
            }
            if (!ok) {
                System.out.println("[TAB] not found: " + arg);
            }
            BridgeCommands.printTabs();
            return;
        }

        if (trimmed.startsWith("!")) {
            BridgeCommands.sendEvalFromStdin(trimmed.substring(1).trim());
            return;
        }

        SessionManager.addMessage("user", line);
        BridgeCommands.sendFromStdin(line);
    }

    // ============================================================
    //  Парсер "path [text]" с поддержкой кавычек.
    //
    //  Примеры:
    //    "C:\file.txt"  текст       → path=C:\file.txt, text=текст
    //    '/tmp/a b.txt' текст       → path=/tmp/a b.txt, text=текст
    //    /tmp/file.txt some text    → path=/tmp/file.txt, text=some text
    //    /tmp/file.txt              → path=/tmp/file.txt, text=""
    // ============================================================
    static String[] parsePathAndRest(String rest) {
        if (rest == null || rest.isEmpty()) return new String[]{ "", "" };

        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            if (end > 0) {
                return new String[]{
                        rest.substring(1, end),
                        rest.substring(end + 1).trim()
                };
            }
            return new String[]{ rest.substring(1), "" };
        }

        if (rest.startsWith("'")) {
            int end = rest.indexOf('\'', 1);
            if (end > 0) {
                return new String[]{
                        rest.substring(1, end),
                        rest.substring(end + 1).trim()
                };
            }
            return new String[]{ rest.substring(1), "" };
        }

        int sp = rest.indexOf(' ');
        if (sp < 0) return new String[]{ rest, "" };
        return new String[]{
                rest.substring(0, sp),
                rest.substring(sp + 1).trim()
        };
    }
}