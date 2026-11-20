package ru.miacomsoft.bridge;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Очередь команд + жизненный цикл активного стрима + роутинг чанков.
 */
public final class CommandDispatcher {

    private static final BlockingQueue<PendingCommand> commandQueue = new LinkedBlockingQueue<>();

    private static volatile String  activeStreamId      = null;
    private static volatile String  activeStreamType    = null;
    private static volatile StreamListener activeListener = null;
    private static volatile boolean activeFromStdin     = false;
    private static volatile boolean activeIsChatCommand = false;
    private static volatile boolean awaitingResponse    = false;
    private static volatile long    lastActivityAt      = System.currentTimeMillis();

    private static volatile String activeServingTabId = null;

    private static final Object streamLock = new Object();
    private static final Set<String> rejectedStreams =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private CommandDispatcher() {}

    // ============================================================
    //  enqueue / dispatch
    // ============================================================
    public static synchronized void enqueue(PendingCommand cmd) {
        commandQueue.offer(cmd);
        trySendNext();
    }

    static synchronized void trySendNext() {
        if (awaitingResponse) return;
        BrowserTab c = TabRegistry.pickActiveForDispatch();
        if (c == null) return;
        PendingCommand cmd = commandQueue.poll();
        if (cmd == null) return;
        awaitingResponse = true;
        activeListener = cmd.listener;
        activeFromStdin = cmd.fromStdin;
        activeIsChatCommand = cmd.isChatCommand;
        activeServingTabId = c.tabId;
        lastActivityAt = System.currentTimeMillis();
        c.queue.offer(cmd.json);
    }

    public static void touchActivity() {
        lastActivityAt = System.currentTimeMillis();
    }

    // ============================================================
    //  watchdog
    // ============================================================
    public static void watchdogTick() {
        if (awaitingResponse
                && System.currentTimeMillis() - lastActivityAt > BridgeConstants.WATCHDOG_TIMEOUT_MS) {
            StreamListener l = activeListener;
            activeListener = null;
            awaitingResponse = false;
            activeFromStdin = false;
            activeIsChatCommand = false;
            activeServingTabId = null;
            if (l != null) {
                try { l.onError("watchdog timeout"); } catch (Exception ignored) {}
            }
            trySendNext();
        }
    }

    // ============================================================
    //  /log: stream chunks — вывод через ConsoleWriter
    // ============================================================
    public static void onStreamChunk(String sid, String text, String sType) {
        if (sid.isEmpty()) return;

        ConsoleWriter cw = ConsoleWriter.get();

        synchronized (streamLock) {
            if (rejectedStreams.contains(sid)) return;

            if (activeStreamId == null) {
                activeStreamId = sid;
                activeStreamType = null;
            } else if (!activeStreamId.equals(sid)) {
                rejectedStreams.add(sid);
                return;
            }

            if (!sType.equals(activeStreamType)) {
                if (activeStreamType != null) cw.finishStream();
                cw.printTag("THINK".equals(sType) ? "[THINK] " : "[DEEPSEEK] ");
                activeStreamType = sType;
            }
            cw.appendStreamText(text);
        }

        StreamListener l = activeListener;
        if (l != null) {
            try { l.onChunk(text, sType); } catch (Exception ignored) {}
        }
    }

    public static void onStreamDone(String sid) {
        if (sid.isEmpty()) return;
        StreamListener l = null;
        boolean wasStdin = false;

        synchronized (streamLock) {
            if (rejectedStreams.remove(sid)) return;
            boolean wasActive = sid.equals(activeStreamId);
            boolean orphan = (activeStreamId == null && awaitingResponse);

            if (wasActive || orphan) {
                if (wasActive && activeStreamType != null) {
                    ConsoleWriter.get().finishStream();
                }
                activeStreamId = null;
                activeStreamType = null;
                activeServingTabId = null;
                l = activeListener;
                wasStdin = activeFromStdin;
                activeListener = null;
                awaitingResponse = false;
                activeFromStdin = false;
                activeIsChatCommand = false;
            }
        }

        if (l != null) {
            try { l.onDone(); } catch (Exception ignored) {}
        }

        trySendNext();

        if (wasStdin) {
            System.out.print("> ");
            System.out.flush();
        }
    }

    // ============================================================
    //  /log: result / error
    //
    //  НОВОЕ: если нет активного listener'а — печатаем всегда
    //  (это результат ручных команд: stop/resume/eval/...).
    // ============================================================
    public static void onResult(String text) {
        if (text.startsWith("sent:")) return;

        StreamListener l = activeListener;
        activeListener = null;
        awaitingResponse = false;
        activeFromStdin = false;
        activeIsChatCommand = false;
        activeServingTabId = null;

        ConsoleWriter.get().ensureNewline();

        if (l != null) {
            try { l.onError(text); } catch (Exception ignored) {}
        } else {
            System.out.println("✓ " + text);
            System.out.print("> ");
            System.out.flush();
        }

        trySendNext();
    }

    public static void onError(String text) {
        StreamListener l = activeListener;
        activeListener = null;
        awaitingResponse = false;
        activeFromStdin = false;
        activeIsChatCommand = false;
        activeServingTabId = null;

        ConsoleWriter.get().ensureNewline();

        if (l != null) {
            try { l.onError(text); } catch (Exception ignored) {}
        } else {
            System.out.println("[!] " + text);
            System.out.print("> ");
            System.out.flush();
        }
        trySendNext();
    }

    // ============================================================
    //  /log: bye / token-limit
    // ============================================================
    public static void onBye(String tabId, String reason) {
        if (tabId == null || tabId.isEmpty()) return;

        BrowserTab c = TabRegistry.findClient(tabId);
        if (c != null) {
            c.closeReason = reason == null ? "" : reason;
            c.active = false;
            c.queue.offer("__close__");
            TabRegistry.removeClient(c);
        }

        TabRegistry.clearSelectedIf(tabId);

        boolean wasServing = tabId.equals(activeServingTabId);

        if (wasServing) {
            StreamListener l = activeListener;
            boolean wasStdin = activeFromStdin;
            activeListener = null;
            awaitingResponse = false;
            activeFromStdin = false;
            activeIsChatCommand = false;
            activeServingTabId = null;
            activeStreamId = null;
            activeStreamType = null;

            if (l != null) {
                try { l.onError("tab closed: " + BridgeUtils.shortId(tabId)); } catch (Exception ignored) {}
            } else if (wasStdin) {
                ConsoleWriter.get().ensureNewline();
                System.out.println("[!] tab closed: " + BridgeUtils.shortId(tabId));
                System.out.print("> ");
                System.out.flush();
            }
        }

        ConsoleWriter.get().ensureNewline();
        System.out.println("[BYE] tab " + BridgeUtils.shortId(tabId)
                + " closed" + (reason != null && !reason.isEmpty() ? " (" + reason + ")" : ""));
        System.out.print("> ");
        System.out.flush();

        trySendNext();
    }

    public static void onTokenLimit(String tabId, String resetOn) {
        BrowserTab c = TabRegistry.findClient(tabId);
        if (c == null) return;
        boolean wasFirstTime = !c.tokenExhausted;
        c.tokenExhausted = true;

        boolean wasServing = tabId.equals(activeServingTabId) && awaitingResponse;
        StreamListener l = null;
        boolean wasStdin = false;

        if (wasServing) {
            synchronized (streamLock) {
                l = activeListener;
                wasStdin = activeFromStdin;
                activeListener = null;
                awaitingResponse = false;
                activeFromStdin = false;
                activeIsChatCommand = false;
                activeServingTabId = null;
                activeStreamId = null;
                activeStreamType = null;
            }
        }

        if (wasFirstTime) {
            ConsoleWriter.get().ensureNewline();
            System.out.println("[LIMIT] tab " + BridgeUtils.shortId(tabId)
                    + " hit usage limit. Reset: " + (resetOn.isEmpty() ? "?" : resetOn));
            System.out.print("> ");
            System.out.flush();
        }

        if (wasServing) {
            TabRegistry.clearSelectedIf(tabId);
            if (l != null) {
                try { l.onError("usage limit reached: " + resetOn); } catch (Exception ignored) {}
            } else if (wasStdin) {
                ConsoleWriter.get().ensureNewline();
                System.out.println("[!] usage limit reached: " + resetOn);
                System.out.print("> ");
                System.out.flush();
            }
            trySendNext();
        }
    }

    // ============================================================
    //  SSE disconnect (finally из /events)
    // ============================================================
    public static synchronized void clearServingTabIf(String tabId) {
        if (tabId != null && tabId.equals(activeServingTabId)) {
            activeServingTabId = null;
        }
    }
}