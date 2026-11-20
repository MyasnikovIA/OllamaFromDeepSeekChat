package ru.miacomsoft.bridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Реестр вкладок: список, выбор, печать, JSON /tabs, hello/mode-state. */
public final class TabRegistry {

    private static final List<BrowserTab> clients = new ArrayList<>();
    private static volatile String selectedTabId = null;

    private TabRegistry() {}

    // ------------------------------------------------------------
    //  базовый доступ
    // ------------------------------------------------------------
    public static BrowserTab findClient(String tabId) {
        if (tabId == null || tabId.isEmpty()) return null;
        synchronized (clients) {
            for (BrowserTab c : clients) {
                if (c.active && c.tabId.equals(tabId)) return c;
            }
        }
        return null;
    }

    public static List<BrowserTab> snapshotActive() {
        List<BrowserTab> out = new ArrayList<>();
        synchronized (clients) {
            for (BrowserTab c : clients) if (c.active) out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------
    //  SSE registration (dedupe by tabId)
    // ------------------------------------------------------------
    public static void registerSse(BrowserTab fresh) {
        synchronized (clients) {
            for (BrowserTab old : new ArrayList<>(clients)) {
                if (old.active && old.tabId.equals(fresh.tabId)) {
                    old.active = false;
                    old.queue.offer("__close__");
                    clients.remove(old);
                    ConsoleWriter.get().ensureNewline();
                    System.out.println("[TAB] replacing stale SSE: " + BridgeUtils.shortId(fresh.tabId));
                    System.out.print("> "); System.out.flush();
                }
            }
            clients.add(fresh);
        }
    }

    public static void removeClient(BrowserTab tab) {
        synchronized (clients) { clients.remove(tab); }
    }

    public static boolean isSuperseded(BrowserTab client) {
        synchronized (clients) {
            for (BrowserTab other : clients) {
                if (other.active && other.tabId.equals(client.tabId) && other != client) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------
    //  выбор вкладки для рассылки команд
    // ------------------------------------------------------------
    public static BrowserTab pickActiveForDispatch() {
        synchronized (clients) {
            if (selectedTabId != null) {
                for (BrowserTab c : clients) {
                    if (c.active && c.tabId.equals(selectedTabId) && !c.tokenExhausted) return c;
                }
                selectedTabId = null;
            }
            for (BrowserTab c : clients) {
                if (c.active && !c.tokenExhausted) return c;
            }
        }
        return null;
    }

    public static BrowserTab pickAnyActive() {
        synchronized (clients) {
            if (selectedTabId != null) {
                for (BrowserTab c : clients) {
                    if (c.active && c.tabId.equals(selectedTabId)) return c;
                }
            }
            for (BrowserTab c : clients) {
                if (c.active) return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    //  selection helpers (from stdin: /tab, /close, /tabs)
    // ------------------------------------------------------------
    public static synchronized boolean selectTabByIndex(int idx) {
        List<BrowserTab> active = snapshotActive();
        if (idx < 1 || idx > active.size()) return false;
        selectedTabId = active.get(idx - 1).tabId;
        return true;
    }

    public static synchronized boolean selectTabById(String tabId) {
        synchronized (clients) {
            for (BrowserTab c : clients) {
                if (c.active && c.tabId.equals(tabId)) {
                    selectedTabId = tabId;
                    return true;
                }
            }
        }
        return false;
    }

    public static synchronized boolean selectTabByPrefix(String prefix) {
        synchronized (clients) {
            for (BrowserTab c : clients) {
                if (c.active && c.tabId.startsWith(prefix)) {
                    selectedTabId = c.tabId;
                    return true;
                }
            }
        }
        return false;
    }

    public static synchronized String getTabIdByIndex(int idx) {
        List<BrowserTab> active = snapshotActive();
        if (idx < 1 || idx > active.size()) return null;
        return active.get(idx - 1).tabId;
    }

    public static synchronized String findActiveTabId(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isEmpty()) return null;
        synchronized (clients) {
            for (BrowserTab c : clients) {
                if (c.active && c.tabId.equals(idOrPrefix)) return c.tabId;
            }
            for (BrowserTab c : clients) {
                if (c.active && c.tabId.startsWith(idOrPrefix)) return c.tabId;
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    //  side-effects for /log
    // ------------------------------------------------------------
    public static void onHello(String tabId, String info) {
        BrowserTab c = findClient(tabId);
        if (c == null) return;
        c.info = info;
        ConsoleWriter.get().ensureNewline();
        System.out.println("[TAB] registered: " + tabId);
        if (info != null && !info.isEmpty()) {
            System.out.println("      " + info);
        }
        System.out.print("> "); System.out.flush();
    }

    public static void onModeState(String tabId, String text) {
        BrowserTab c = findClient(tabId);
        if (c == null) return;
        try {
            JSONObject j = new JSONObject(text);
            boolean thinking    = j.optBoolean("thinking", false);
            boolean smartSearch = j.optBoolean("smartSearch", false);
            boolean changed = !c.modesReported
                    || (c.thinking != thinking)
                    || (c.smartSearch != smartSearch);
            c.thinking    = thinking;
            c.smartSearch = smartSearch;
            if (changed) {
                c.modesReported = true;
                ConsoleWriter.get().ensureNewline();
                System.out.println("[MODE " + BridgeUtils.shortId(tabId) + "]"
                        + " thinking=" + thinking
                        + " smartSearch=" + smartSearch);
                System.out.print("> "); System.out.flush();
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------
    //  cleanup
    // ------------------------------------------------------------
    public static void clearSelectedIf(String tabId) {
        if (tabId != null && tabId.equals(selectedTabId)) selectedTabId = null;
    }

    // ------------------------------------------------------------
    //  печать для stdin
    // ------------------------------------------------------------
    public static synchronized void printTabs() {
        List<BrowserTab> active = snapshotActive();
        ConsoleWriter.get().ensureNewline();
        System.out.println("[TABS] active: " + active.size()
                + (selectedTabId != null ? " (selected: " + selectedTabId + ")" : " (selected: auto)"));
        for (int i = 0; i < active.size(); i++) {
            BrowserTab c = active.get(i);
            String marker = c.tabId.equals(selectedTabId) ? "▶" : " ";
            String status = c.tokenExhausted ? "[EXHAUSTED]" : "[ok]";
            System.out.println("  " + marker + " #" + (i + 1)
                    + " " + c.tabId + " " + status
                    + " thinking=" + c.thinking
                    + " smartSearch=" + c.smartSearch);
            if (c.info != null && !c.info.isEmpty()) {
                System.out.println("      " + c.info);
            }
        }
        System.out.print("> "); System.out.flush();
    }

    public static synchronized void printState() {
        BrowserTab c = pickActiveForDispatch();
        ConsoleWriter.get().ensureNewline();
        if (c == null) {
            System.out.println("[STATE] no active tab");
        } else {
            System.out.println("[STATE] " + c.tabId
                    + " thinking=" + c.thinking
                    + " smartSearch=" + c.smartSearch
                    + " tokenExhausted=" + c.tokenExhausted);
        }
        System.out.print("> "); System.out.flush();
    }

    // ------------------------------------------------------------
    //  GET /tabs JSON
    // ------------------------------------------------------------
    public static JSONObject toJson() {
        JSONArray arr = new JSONArray();
        synchronized (clients) {
            for (BrowserTab c : clients) {
                if (!c.active) continue;
                JSONObject o = new JSONObject();
                o.put("tabId", c.tabId);
                o.put("info", c.info);
                o.put("thinking", c.thinking);
                o.put("smartSearch", c.smartSearch);
                o.put("tokenExhausted", c.tokenExhausted);
                o.put("selected", c.tabId.equals(selectedTabId));
                arr.put(o);
            }
        }
        JSONObject resp = new JSONObject();
        resp.put("count", arr.length());
        resp.put("tabs", arr);
        return resp;
    }
}