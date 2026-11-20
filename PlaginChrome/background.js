// background.js — service worker расширения.
// Хранит недавние стримы, карту customTabId → chromeTabId
// и обслуживает команды управления вкладками от content script.

const recent = [];
const MAX = 500;

/** customTabId (UUID из content.js) → chrome.tab.id */
const tabMap = new Map();

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg) return;

  // ---- существующее: сбор чанков ----
  if (msg.type === "ds_chunk") {
    recent.push({ t: Date.now(), phase: msg.phase, streamId: msg.streamId, chunk: msg.chunk });
    if (recent.length > MAX) recent.shift();
    sendResponse({ ok: true });
    return true;
  }

  if (msg.type === "get_recent") {
    sendResponse({ chunks: recent });
    return true;
  }

  // ============================================================
  //  НОВОЕ: регистрация вкладки (content script → background)
  //  sender.tab.id — это реальный chrome.tab.id текущей вкладки
  // ============================================================
  if (msg.type === "register_tab") {
    const chromeTabId = sender.tab && sender.tab.id;
    if (chromeTabId != null && msg.tabId) {
      tabMap.set(msg.tabId, chromeTabId);
      console.info("[bg] registered tab", msg.tabId, "-> chrome", chromeTabId);
      sendResponse({ ok: true, chromeTabId });
    } else {
      sendResponse({ ok: false, error: "no sender.tab.id" });
    }
    return true;
  }

  // ============================================================
  //  НОВОЕ: открыть новую вкладку
  // ============================================================
  if (msg.type === "open_tab") {
    const url = msg.url || "https://chat.deepseek.com/";
    const active = msg.active !== false; // по умолчанию foreground
    try {
      chrome.tabs.create({ url, active }, (tab) => {
        if (chrome.runtime.lastError) {
          sendResponse({ ok: false, error: chrome.runtime.lastError.message });
        } else {
          console.info("[bg] opened tab", tab.id, url);
          sendResponse({ ok: true, chromeTabId: tab.id });
        }
      });
    } catch (e) {
      sendResponse({ ok: false, error: String(e) });
    }
    return true;
  }

  // ============================================================
  //  НОВОЕ: закрыть конкретную вкладку
  // ============================================================
  if (msg.type === "close_tab") {
    const customId = msg.tabId;
    const chromeTabId = tabMap.get(customId);
    if (chromeTabId == null) {
      sendResponse({ ok: false, error: "tab not found: " + customId });
      return true;
    }
    try {
      chrome.tabs.remove(chromeTabId, () => {
        tabMap.delete(customId);
        if (chrome.runtime.lastError) {
          sendResponse({ ok: false, error: chrome.runtime.lastError.message });
        } else {
          console.info("[bg] closed tab", customId, "(chrome", chromeTabId + ")");
          sendResponse({ ok: true });
        }
      });
    } catch (e) {
      sendResponse({ ok: false, error: String(e) });
    }
    return true;
  }

  // ============================================================
  //  НОВОЕ: закрыть ВСЕ вкладки chat.deepseek.com/*
  //  (требуется host_permissions на chat.deepseek.com/*,
  //   либо разрешение "tabs" в manifest.json)
  // ============================================================
  if (msg.type === "close_all_deepseek") {
    try {
      chrome.tabs.query({ url: "https://chat.deepseek.com/*" }, (tabs) => {
        if (chrome.runtime.lastError) {
          sendResponse({ ok: false, error: chrome.runtime.lastError.message });
          return;
        }
        const ids = (tabs || [])
            .map((t) => t.id)
            .filter((id) => id != null);
        if (ids.length === 0) {
          sendResponse({ ok: true, closed: 0 });
          return;
        }
        chrome.tabs.remove(ids, () => {
          // чистим карту
          for (const [k, v] of tabMap.entries()) {
            if (ids.includes(v)) tabMap.delete(k);
          }
          console.info("[bg] closed", ids.length, "deepseek tabs");
          sendResponse({ ok: true, closed: ids.length });
        });
      });
    } catch (e) {
      sendResponse({ ok: false, error: String(e) });
    }
    return true;
  }

  return false;
});