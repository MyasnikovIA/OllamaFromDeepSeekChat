package ru.miacomsoft;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Точка входа.
 *
 * stdin:
 *   обычная строка    → sendToDeepSeek(text)
 *   "!js..."          → eval в консоли страницы
 *   "/new"            → создать новый чат
 *   "/think on"       → включить режим размышления
 *   "/think off"      → выключить режим размышления
 *   "/search on"      → включить умный поиск
 *   "/search off"     → выключить умный поиск
 *   "/file <path> [text]"  → отправить файл (можно с текстом)
 *   "/tabs"           → список открытых вкладок DeepSeek
 *   "/tab <n|id>"     → выбрать вкладку по номеру или tabId (можно префикс)
 *   "/state"          → показать режимы активной вкладки
 *   "/help"           → подсказка
 */
public class Main {

    public static final int PORT = 11434;

    public static void main(String[] args) throws Exception {
        boolean noBrowser = false;
        for (String arg : args) {
            if ("--no-browser".equals(arg)) {
                noBrowser = true;
            } else if (arg.startsWith("--extension=")) {
                System.setProperty("ds.extension.dir", arg.substring("--extension=".length()));
            } else if (arg.startsWith("--profile=")) {
                System.setProperty("ds.profile.dir", arg.substring("--profile=".length()));
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        BridgeService.register(server);
        OllamaApiService.register(server);
        server.start();

        printBanner();

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        sched.scheduleAtFixedRate(BridgeService::watchdogTick, 10, 10, TimeUnit.SECONDS);

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
        System.out.println("[SERVER] Ollama API : http://localhost:" + PORT + "/api/tags");
        System.out.println("[SERVER] Bridge SSE : http://localhost:" + PORT + "/events?tabId=...");
        System.out.println("[SERVER] Bridge log : http://localhost:" + PORT + "/log");
        System.out.println("[SERVER] Tabs list  : http://localhost:" + PORT + "/tabs");
        System.out.println();
        System.out.println("  <текст>              → sendToDeepSeek(text)");
        System.out.println("  ! <js>               → eval в консоли страницы");
        System.out.println("  /new                 → создать новый чат");
        System.out.println("  /think on            → включить режим размышления");
        System.out.println("  /think off           → выключить режим размышления");
        System.out.println("  /search on           → включить умный поиск");
        System.out.println("  /search off          → выключить умный поиск");
        System.out.println("  /file <path> [text]  → отправить файл в DeepSeek (с текстом)");
        System.out.println("  /tabs                → список открытых вкладок DeepSeek");
        System.out.println("  /tab <n|id>          → выбрать вкладку (#n или tabId/префикс)");
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
            BridgeService.sendNewChatFromStdin();
            return;
        }

        if (trimmed.equals("/think on") || trimmed.equals("/think")) {
            BridgeService.sendEnableThinkingFromStdin();
            return;
        }

        if (trimmed.equals("/think off")) {
            BridgeService.sendDisableThinkingFromStdin();
            return;
        }

        if (trimmed.equals("/search on") || trimmed.equals("/search")) {
            BridgeService.sendEnableSmartSearchFromStdin();
            return;
        }

        if (trimmed.equals("/search off")) {
            BridgeService.sendDisableSmartSearchFromStdin();
            return;
        }

        // ---------- /file <path> [text] ----------
        if (trimmed.startsWith("/file ")) {
            String rest = trimmed.substring("/file ".length()).trim();
            if (rest.isEmpty()) {
                System.out.println("[FILE] usage: /file <path> [text]");
                System.out.print("> ");
                System.out.flush();
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
            BridgeService.sendFileFromStdin(path, text);
            return;
        }

        // ---------- вкладки ----------
        if (trimmed.equals("/tabs")) {
            BridgeService.printTabs();
            return;
        }

        if (trimmed.equals("/state")) {
            BridgeService.printState();
            return;
        }

        if (trimmed.startsWith("/tab ")) {
            String arg = trimmed.substring("/tab ".length()).trim();
            if (arg.isEmpty()) {
                System.out.println("[TAB] usage: /tab <n|id>");
                System.out.print("> ");
                System.out.flush();
                return;
            }
            boolean ok;
            try {
                int idx = Integer.parseInt(arg);
                ok = BridgeService.selectTabByIndex(idx);
            } catch (NumberFormatException e) {
                ok = BridgeService.selectTabById(arg)
                        || BridgeService.selectTabByPrefix(arg);
            }
            if (!ok) {
                System.out.println("[TAB] not found: " + arg);
            }
            BridgeService.printTabs();
            return;
        }

        if (trimmed.startsWith("!")) {
            BridgeService.sendEvalFromStdin(trimmed.substring(1).trim());
            return;
        }

        BridgeService.sendFromStdin(line);
    }
}