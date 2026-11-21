package ru.miacomsoft;

import com.sun.net.httpserver.HttpServer;
import ru.miacomsoft.bridge.BridgeCommands;
import ru.miacomsoft.bridge.BridgeConstants;
import ru.miacomsoft.bridge.BridgeRouter;
import ru.miacomsoft.bridge.ConsoleWriter;
import ru.miacomsoft.config.Config;
import ru.miacomsoft.ollama.OllamaConstants;
import ru.miacomsoft.ollama.OllamaRouter;
import ru.miacomsoft.session.SessionManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
            // printJsonError уже отработал внутри Config.load
            System.err.println("[CONFIG] JSON parse failed. Exiting.");
            System.exit(1);
            return;
        } catch (java.io.IOException e) {
            System.err.println("[CONFIG] read failed: " + e.getMessage() + " — using defaults.");
        }

        // Применить значения из конфига к статическим полям и ConsoleWriter.
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

        if (!noBrowser && !"true".equals(System.getProperty("ds.no.browser"))) {
            ChromeLauncher.openDeepSeek();
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
        System.out.println("[SERVER] debug JSON : "
                + (OllamaConstants.DEBUG ? "ON" : "off"));
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
        System.out.println("  /file <path> [text]  → отправить файл в DeepSeek (с текстом)");
        System.out.println("  /tabs                → список открытых вкладок DeepSeek");
        System.out.println("  /tab <n|id>          → выбрать вкладку (#n или tabId/префикс)");
        System.out.println("  /opentab [url]       → открыть новую вкладку DeepSeek");
        System.out.println("  /newtab [url]        → алиас для /opentab");
        System.out.println("  /close <n|id>        → закрыть вкладку (#n, tabId или префикс)");
        System.out.println("  /closeall            → закрыть ВСЕ вкладки chat.deepseek.com/*");
        System.out.println("  /state               → показать режимы активной вкладки");
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

        if (trimmed.startsWith("/file ")) {
            String rest = trimmed.substring("/file ".length()).trim();
            if (rest.isEmpty()) {
                System.out.println("[FILE] usage: /file <path> [text]");
                System.out.print("> "); System.out.flush();
                return;
            }
            String path;
            String text = "";
            if (rest.startsWith("\"")) {
                int end = rest.indexOf('"', 1);
                if (end > 0) {
                    path = rest.substring(1, end);
                    text = rest.substring(end + 1).trim();
                } else {
                    path = rest.substring(1);
                }
            } else if (rest.startsWith("'")) {
                int end = rest.indexOf('\'', 1);
                if (end > 0) {
                    path = rest.substring(1, end);
                    text = rest.substring(end + 1).trim();
                } else {
                    path = rest.substring(1);
                }
            } else {
                int sp = rest.indexOf(' ');
                if (sp < 0) {
                    path = rest;
                } else {
                    path = rest.substring(0, sp);
                    text = rest.substring(sp + 1).trim();
                }
            }

            SessionManager.addMessage("user",
                    "Отправлен файл: " + path + (text.isEmpty() ? "" : " | Текст: " + text));

            BridgeCommands.sendFileFromStdin(path, text);
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
}