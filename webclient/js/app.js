/**
 * Точка входа. Связывает UI, API и контроллер чата.
 * Хранит состояние (список вкладок, активная вкладка, режимы)
 * и синхронизирует его с сервером.
 */
(function () {
  "use strict";

  const els = {
    connectScreen:  document.getElementById("connect-screen"),
    chatScreen:     document.getElementById("chat-screen"),
    connectUrl:     document.getElementById("connect-url"),
    connectBtn:     document.getElementById("connect-btn"),
    connectStatus:  document.getElementById("connect-status"),

    newChatBtn:     document.getElementById("new-chat-btn"),
    refreshTabsBtn: document.getElementById("refresh-tabs-btn"),
    tabsList:       document.getElementById("tabs-list"),

    thinkToggle:    document.getElementById("mode-think"),
    searchToggle:   document.getElementById("mode-search"),

    statusPill:     document.getElementById("status-pill"),

    chatTitle:      document.getElementById("chat-title"),
    chatSubtitle:   document.getElementById("chat-subtitle"),

    messages:       document.getElementById("messages"),
    emptyState:     document.getElementById("empty-state"),

    composer:       document.getElementById("composer-input"),
    sendBtn:        document.getElementById("send-btn"),
    stopBtn:        document.getElementById("stop-btn"),
  };

  const state = {
    api: null,
    activeTabId: null,
    tabs: [],
    modes: { thinking: false, smartSearch: false },
    chat: null,
    pollTimer: null,
  };

  // ============================================================
  //  Подключение
  // ============================================================
  function initConnectScreen() {
    els.connectUrl.value = window.DSConfig.getBaseUrl();

    els.connectBtn.addEventListener("click", connect);
    els.connectUrl.addEventListener("keydown", (e) => {
      if (e.key === "Enter") connect();
    });
  }

  async function connect() {
    const url = els.connectUrl.value.trim().replace(/\/+$/, "");
    if (!url) {
      setConnectStatus("Введите URL", true);
      return;
    }

    els.connectBtn.disabled = true;
    setConnectStatus("Подключение…", false);

    try {
      const api = new window.BridgeApi(url);
      await api.ping();
      await api.tabs(); // проверка, что /api/command отвечает

      window.DSConfig.setBaseUrl(url);
      state.api = api;

      setConnectStatus("OK", false);
      enterChat();
    } catch (e) {
      setConnectStatus("Ошибка: " + e.message, true);
    } finally {
      els.connectBtn.disabled = false;
    }
  }

  function setConnectStatus(text, isError) {
    els.connectStatus.textContent = text;
    els.connectStatus.classList.toggle("error", !!isError);
    els.connectStatus.classList.toggle("success", !isError && text === "OK");
  }

  // ============================================================
  //  Вход в чат
  // ============================================================
  async function enterChat() {
    UI.hide(els.connectScreen);
    UI.show(els.chatScreen);

    // chat-контроллер
    state.chat = new window.ChatController(state.api, {
      messages:   els.messages,
      emptyState: els.emptyState,
      stopBtn:    els.stopBtn,
    });

    bindChatEvents();
    bindModes();
    bindSidebar();

    await refreshAll();
    startPolling();
  }

  // ============================================================
  //  Периодическая синхронизация
  // ============================================================
  function startPolling() {
    if (state.pollTimer) clearInterval(state.pollTimer);
    state.pollTimer = setInterval(() => {
      refreshAll().catch(() => {});
    }, 5000);
  }

  async function refreshAll() {
    if (!state.api) return;
    try {
      const tabs = await state.api.tabs();
      state.tabs = tabs;

      // авто-выбор активной вкладки, если её нет
      if (!state.activeTabId) {
        const selected = tabs.find(t => t.selected) || tabs[0];
        if (selected) state.activeTabId = selected.tabId;
      }

      // если активная вкладка пропала — выбрать любую
      if (state.activeTabId && !tabs.some(t => t.tabId === state.activeTabId)) {
        state.activeTabId = tabs[0] ? tabs[0].tabId : null;
      }

      // переключить на сервере, если наш выбор ≠ серверный
      if (state.activeTabId) {
        const current = tabs.find(t => t.tabId === state.activeTabId);
        if (current && !current.selected) {
          try { await state.api.selectTab(state.activeTabId); } catch (_) {}
        }
      }

      // подтянуть режимы активной вкладки
      if (state.activeTabId) {
        const t = tabs.find(x => x.tabId === state.activeTabId);
        if (t) {
          state.modes.thinking = !!t.thinking;
          state.modes.smartSearch = !!t.smartSearch;
        }
      }

      renderTabs();
      renderModes();
      renderStatus(true);
    } catch (e) {
      renderStatus(false, e.message);
      throw e;
    }
  }

  function renderTabs() {
    UI.renderTabs(els.tabsList, state.tabs, state.activeTabId, async (tabId) => {
      state.activeTabId = tabId;
      try { await state.api.selectTab(tabId); } catch (_) {}
      renderTabs();
      // сразу обновим режимы
      await refreshAll();
    });

    if (state.activeTabId) {
      const t = state.tabs.find(x => x.tabId === state.activeTabId);
      els.chatTitle.textContent = "Чат";
      els.chatSubtitle.textContent = t
        ? t.tabId + (t.thinking ? " · 🧠" : "") + (t.smartSearch ? " · 🔍" : "")
        : "нет активной вкладки";
    } else {
      els.chatTitle.textContent = "Чат";
      els.chatSubtitle.textContent = "нет активной вкладки";
    }
  }

  function renderModes() {
    UI.setModeCheckboxes(els.thinkToggle, els.searchToggle, state.modes);
  }

  function renderStatus(ok, detail) {
    UI.setStatus(els.statusPill, ok, ok ? "Подключено" : ("Нет связи: " + (detail || "")));
  }

  // ============================================================
  //  События чата
  // ============================================================
  function bindChatEvents() {
    // авто-высота textarea
    els.composer.addEventListener("input", () => {
      els.composer.style.height = "auto";
      els.composer.style.height = Math.min(els.composer.scrollHeight, 160) + "px";
    });

    els.composer.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        sendCurrent();
      }
    });

    els.sendBtn.addEventListener("click", sendCurrent);

    els.stopBtn.addEventListener("click", async () => {
      if (!state.chat) return;
      await state.chat.stop();
    });

    // двойной клик по «Стоп» — продолжить (просто как фича)
    els.stopBtn.addEventListener("dblclick", async () => {
      if (!state.chat) return;
      await state.chat.resume();
    });
  }

  async function sendCurrent() {
    const text = els.composer.value.trim();
    if (!text || !state.chat) return;
    if (!state.activeTabId) {
      UI.appendMessage(els.messages, "Нет активной вкладки DeepSeek", "error");
      return;
    }
    els.composer.value = "";
    els.composer.style.height = "auto";
    await state.chat.send(text);
  }

  // ============================================================
  //  Режимы
  // ============================================================
  function bindModes() {
    els.thinkToggle.addEventListener("change", async () => {
      if (!state.api) return;
      const on = els.thinkToggle.checked;
      try {
        await state.api.setThinking(on);
        state.modes.thinking = on;
        await refreshAll();
      } catch (e) {
        els.thinkToggle.checked = !on;
        UI.appendMessage(els.messages, "DeepThink: " + e.message, "error");
      }
    });

    els.searchToggle.addEventListener("change", async () => {
      if (!state.api) return;
      const on = els.searchToggle.checked;
      try {
        await state.api.setSearch(on);
        state.modes.smartSearch = on;
        await refreshAll();
      } catch (e) {
        els.searchToggle.checked = !on;
        UI.appendMessage(els.messages, "Search: " + e.message, "error");
      }
    });
  }

  // ============================================================
  //  Сайдбар
  // ============================================================
  function bindSidebar() {
    els.newChatBtn.addEventListener("click", async () => {
      if (!state.chat) return;
      await state.chat.newChat();
    });

    els.refreshTabsBtn.addEventListener("click", () => {
      refreshAll().catch(() => {});
    });
  }

  // ============================================================
  //  Старт
  // ============================================================
  document.addEventListener("DOMContentLoaded", () => {
    initConnectScreen();

    // если уже был успешный коннект — попробовать автоматически
    const saved = window.DSConfig.getBaseUrl();
    if (saved) {
      els.connectUrl.value = saved;
      // тихая автопопытка (без спиннера)
      (async () => {
        try {
          const api = new window.BridgeApi(saved);
          await api.ping();
          state.api = api;
          enterChat();
        } catch (_) { /* оставить экран подключения */ }
      })();
    }
  });
})();