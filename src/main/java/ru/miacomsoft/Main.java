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
 *   "/help"           → подсказка
 */
public class Main {

    public static final int PORT = 8085;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        BridgeService.register(server);
        OllamaApiService.register(server);
        server.start();

        printBanner();

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        sched.scheduleAtFixedRate(BridgeService::watchdogTick, 10, 10, TimeUnit.SECONDS);

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
        System.out.println("[SERVER] Bridge SSE : http://localhost:" + PORT + "/events");
        System.out.println("[SERVER] Bridge log : http://localhost:" + PORT + "/log");
        System.out.println();
        System.out.println("  <текст>          → sendToDeepSeek(text)");
        System.out.println("  ! <js>           → eval в консоли страницы");
        System.out.println("  /new             → создать новый чат");
        System.out.println("  /think on        → включить режим размышления");
        System.out.println("  /think off       → выключить режим размышления");
        System.out.println("  /help            → эта подсказка");
        System.out.println("========================================================");
        System.out.print("> ");
        System.out.flush();
    }

    static void handleStdin(String line) {
        String trimmed = line.trim();

        // /help
        if (trimmed.equals("/help") || trimmed.equals("/?")) {
            printBanner();
            return;
        }

        // /new
        if (trimmed.equals("/new") || trimmed.equals("/newchat")) {
            BridgeService.sendNewChatFromStdin();
            return;
        }

        // /think on|off
        if (trimmed.equals("/think on") || trimmed.equals("/think")) {
            if (trimmed.equals("/think")) {
                // без аргумента — просто включить
                BridgeService.sendEnableThinkingFromStdin();
                return;
            }
            BridgeService.sendEnableThinkingFromStdin();
            return;
        }
        if (trimmed.equals("/think off")) {
            BridgeService.sendDisableThinkingFromStdin();
            return;
        }

        // !js...
        if (trimmed.startsWith("!")) {
            BridgeService.sendEvalFromStdin(trimmed.substring(1).trim());
            return;
        }

        // обычный текст → sendToDeepSeek
        BridgeService.sendFromStdin(line);
    }
}

