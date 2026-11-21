(function () {
  "use strict";

  const els = {
    connectScreen:  document.getElementById("connect-screen"),
    chatScreen:     document.getElementById("chat-screen"),
    connectUrl:     document.getElementById("connect-url"),
    connectBtn:     document.getElementById("connect-btn"),
    connectStatus:  document.getElementById("connect-status"),

    newChatBtn:     document.getElementById("new-chat-btn"),
    newChatTopBtn:  document.getElementById("new-chat-btn-top"),
    refreshTabsBtn: document.getElementById("refresh-tabs-btn"),
    changeServerBtn: document.getElementById("change-server-btn"),
    tabsList:       document.getElementById("tabs-list"),
    browsersList:   document.getElementById("browsers-list"),

    thinkToggle:    document.getElementById("mode-think"),
    searchToggle:   document.getElementById("mode-search"),

    statusPill:     document.getElementById("status-pill"),

    chatTitle:      document.getElementById("chat-title"),
    chatSubtitle:   document.getElementById("chat-subtitle"),

    messagesContainer: document.getElementById("messages-container"),

    composer:       document.getElementById("composer-input"),
    sendBtn:        document.getElementById("send-btn"),
    stopBtn:        document.getElementById("stop-btn"),

    attachBtn:      document.getElementById("attach-btn"),
    fileInput:      document.getElementById("file-input"),
  };

  const state = {
    api: null,
    activeTabId: null,
    tabs: [],
    browsers: [],
    modes: { thinking: false, smartSearch: false },
    chat: null,
    pollTimer: null,
    tabContainers: {},
    tabControllers: {},
    pendingFiles: [],
    isChangingServer: false,
  };

  // ----------------------------------------------------------
  //  Подключение
  // ----------------------------------------------------------
  function initConnectScreen() {
    const saved = window.DSConfig.getBaseUrl();
    if (saved) {
      els.connectUrl.value = saved;
      autoConnect(saved);
    } else {
      UI.show(els.connectScreen);
    }
    els.connectBtn.addEventListener("click", () => connect(state.isChangingServer));
    els.connectUrl.addEventListener("keydown", (e) => {
      if (e.key === "Enter") connect(state.isChangingServer);
    });
  }

  async function autoConnect(url) {
    try {
      const api = new window.BridgeApi(url);
      await api.ping();
      state.api = api;
      await enterChat();
    } catch (_) {
      UI.show(els.connectScreen);
    }
  }

  async function connect(isChange) {
    const url = els.connectUrl.value.trim().replace(/\/+$/, "");
    if (!url) { setConnectStatus("Введите URL", true); return; }

    els.connectBtn.disabled = true;
    setConnectStatus("Подключение…", false);

    try {
      const api = new window.BridgeApi(url);
      await api.ping();
      window.DSConfig.setBaseUrl(url);
      state.api = api;
      setConnectStatus("OK", false);
      UI.hide(els.connectScreen);

      if (isChange) {
        window.location.reload();
      } else {
        await enterChat();
      }
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

  // ----------------------------------------------------------
  //  Инициализация чата
  // ----------------------------------------------------------
  async function enterChat() {
    UI.hide(els.connectScreen);
    UI.show(els.chatScreen);

    bindChatEvents();
    bindModes();
    bindSidebar();
    bindAttach();

    try {
      await refreshAll();
      await refreshBrowsers();
    } catch (e) {
      renderStatus(false, e.message);
    }
    startPolling();
  }

  function startPolling() {
    if (state.pollTimer) clearInterval(state.pollTimer);
    state.pollTimer = setInterval(() => { refreshAll().catch(() => {}); }, 5000);
  }

  // ----------------------------------------------------------
  //  Синхронизация с сервером
  // ----------------------------------------------------------
  async function refreshAll() {
    if (!state.api) return;
    try {
      const tabs = await state.api.tabs();
      state.tabs = tabs;

      if (!state.activeTabId) {
        const selected = tabs.find(t => t.selected) || tabs[0];
        if (selected) state.activeTabId = selected.tabId;
      }
      if (state.activeTabId && !tabs.some(t => t.tabId === state.activeTabId)) {
        state.activeTabId = tabs[0] ? tabs[0].tabId : null;
      }

      if (state.activeTabId) {
        const current = tabs.find(t => t.tabId === state.activeTabId);
        if (current && !current.selected) {
          try { await state.api.selectTab(state.activeTabId); } catch (_) {}
        }
      }

      if (state.activeTabId) {
        try {
          const st = await state.api.state();
          if (st && st.active) {
            state.modes.thinking    = !!st.active.thinking;
            state.modes.smartSearch = !!st.active.smartSearch;
          } else {
            state.modes.thinking = false;
            state.modes.smartSearch = false;
          }
        } catch (_) {}
      } else {
        state.modes.thinking = false;
        state.modes.smartSearch = false;
      }

      renderTabs();
      renderBrowsers();
      renderModes();
      renderStatus(true);

      if (state.activeTabId && state.tabContainers[state.activeTabId]) {
        switchTabUI(state.activeTabId);
      }
    } catch (e) {
      renderStatus(false, e.message);
      throw e;
    }
  }

  async function refreshBrowsers() {
    if (!state.api) return;
    try {
      state.browsers = await state.api.browsers();
      renderBrowsers();
    } catch (e) {
      console.warn("Failed to fetch browsers:", e);
    }
  }

  // ----------------------------------------------------------
  //  Рендер
  // ----------------------------------------------------------
  function renderTabs() {
    UI.renderTabs(els.tabsList, state.tabs, state.activeTabId, selectAndRefresh, async (tabId) => {
      try {
        await state.api.cmd("closeTab", { tabId });
        UI.removeTabUI(tabId, state.tabContainers);
        delete state.tabControllers[tabId];
        if (state.activeTabId === tabId) {
          state.activeTabId = null;
          els.chatTitle.textContent = "Чат";
          els.chatSubtitle.textContent = "нет активной вкладки";
          els.messagesContainer.innerHTML = "";
        }
        state.tabs = state.tabs.filter(t => t.tabId !== tabId);
        renderTabs();
        setTimeout(() => refreshAll().catch(() => {}), 1000);
      } catch (e) {
        alert("Ошибка закрытия вкладки: " + e.message);
        refreshAll().catch(() => {});
      }
    });

    if (state.activeTabId) {
      const t = state.tabs.find(x => x.tabId === state.activeTabId);
      if (t) {
        const displayInfo = UI.getTabDisplayInfo(state.activeTabId, state.tabs, state.browsers);
        const think  = state.modes.thinking    ? " 🧠" : "";
        const search = state.modes.smartSearch ? " 🔍" : "";
        els.chatTitle.textContent = "Чат";
        els.chatSubtitle.textContent = displayInfo + think + search;
      } else {
        els.chatTitle.textContent = "Чат";
        els.chatSubtitle.textContent = "нет активной вкладки";
      }
    } else {
      els.chatTitle.textContent = "Чат";
      els.chatSubtitle.textContent = "нет активной вкладки";
    }
  }

  function renderBrowsers() {
    UI.renderBrowsers(els.browsersList, state.browsers, async (index) => {
      try {
        await state.api.openBrowser(index);
        setTimeout(() => refreshAll().catch(() => {}), 3000);
      } catch (e) {
        alert("Ошибка запуска браузера: " + e.message);
      }
    });
  }

  function renderModes() {
    UI.setModeCheckboxes(els.thinkToggle, els.searchToggle, state.modes);
  }

  function renderStatus(ok, detail) {
    UI.setStatus(els.statusPill, ok, ok ? "Подключено" : ("Нет связи: " + (detail || "")));
  }

  // ----------------------------------------------------------
  //  Переключение вкладок
  // ----------------------------------------------------------
  async function selectAndRefresh(tabId) {
    // При смене вкладки сбрасываем прикреплённые файлы,
    // чтобы они случайно не улетели в чужой чат
    if (tabId !== state.activeTabId) {
      state.pendingFiles = [];
      if (els.fileInput) els.fileInput.value = "";
      updateAttachPlaceholder();
    }

    state.activeTabId = tabId;
    try { await state.api.selectTab(tabId); } catch (_) {}

    try {
      const st = await state.api.state();
      if (st && st.active) {
        state.modes.thinking    = !!st.active.thinking;
        state.modes.smartSearch = !!st.active.smartSearch;
      }
    } catch (_) {}

    renderTabs();
    renderModes();
    switchTabUI(tabId);
  }

  function switchTabUI(tabId) {
    for (const id in state.tabContainers) {
      state.tabContainers[id].classList.add("hidden");
    }

    if (!state.tabContainers[tabId]) {
      const div = document.createElement("div");
      div.className = "messages";
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.innerHTML = `
        <div class="empty-icon">💬</div>
        <div class="empty-title">Начните диалог</div>
        <div class="empty-sub">Отправьте сообщение, чтобы DeepSeek ответил</div>
      `;
      div.appendChild(empty);

      els.messagesContainer.appendChild(div);
      state.tabContainers[tabId] = div;

      state.tabControllers[tabId] = new window.ChatController(state.api, {
        messages: div,
        emptyState: empty,
        stopBtn: els.stopBtn,
        composer: els.composer,
      });
    }

    state.tabContainers[tabId].classList.remove("hidden");
    state.chat = state.tabControllers[tabId];
  }

  // ----------------------------------------------------------
  //  Composer / send
  // ----------------------------------------------------------
  function bindChatEvents() {
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
    els.stopBtn.addEventListener("dblclick", async () => {
      if (!state.chat) return;
      await state.chat.resume();
    });
  }

  async function sendCurrent() {
    const text = els.composer.value.trim();
    if (!text && state.pendingFiles.length === 0) return;
    if (!state.chat) return;
    if (!state.activeTabId) {
      alert("Нет активной вкладки DeepSeek");
      return;
    }

    const filesToSend = state.pendingFiles.slice();
    state.pendingFiles = [];
    if (els.fileInput) els.fileInput.value = "";
    els.composer.value = "";
    els.composer.style.height = "auto";
    updateAttachPlaceholder();

    await state.chat.send(text, filesToSend);
  }

  // ----------------------------------------------------------
  //  Прикрепление файлов
  // ----------------------------------------------------------
  function bindAttach() {
    if (!els.attachBtn || !els.fileInput) return;

    els.attachBtn.addEventListener("click", () => els.fileInput.click());

    els.fileInput.addEventListener("change", () => {
      const picked = Array.from(els.fileInput.files || []);
      state.pendingFiles = state.pendingFiles.concat(picked);
      els.fileInput.value = "";
      updateAttachPlaceholder();
    });
  }

  function updateAttachPlaceholder() {
    if (!els.composer) return;
    const n = state.pendingFiles.length;

    // Обновляем title у кнопки 📎
    if (els.attachBtn) {
      els.attachBtn.title = n === 0
          ? "Прикрепить файл"
          : `Прикрепить файл (уже выбрано: ${n})`;
    }

    if (n === 0) {
      els.composer.placeholder = "Сообщение для DeepSeek… (Enter — отправить, Shift+Enter — перенос)";
      return;
    }
    const names = state.pendingFiles.map(f => f.name).join(", ");
    const short = names.length > 60 ? names.slice(0, 57) + "…" : names;
    els.composer.placeholder = `Прикреплено (${n}): ${short}`;
  }

  // ----------------------------------------------------------
  //  Режимы
  // ----------------------------------------------------------
  function bindModes() {
    els.thinkToggle.addEventListener("change", async () => {
      if (!state.api) return;
      const on = els.thinkToggle.checked;
      try {
        await state.api.setThinking(on);
        state.modes.thinking = on;
        renderTabs();
      } catch (e) {
        els.thinkToggle.checked = !on;
        if (state.chat) UI.appendMessage(state.chat.els.messages, "DeepThink: " + e.message, "error");
      }
    });

    els.searchToggle.addEventListener("change", async () => {
      if (!state.api) return;
      const on = els.searchToggle.checked;
      try {
        await state.api.setSearch(on);
        state.modes.smartSearch = on;
        renderTabs();
      } catch (e) {
        els.searchToggle.checked = !on;
        if (state.chat) UI.appendMessage(state.chat.els.messages, "Search: " + e.message, "error");
      }
    });
  }

  // ----------------------------------------------------------
  //  Sidebar
  // ----------------------------------------------------------
  function bindSidebar() {
    const doNewChat = async () => {
      // Сбрасываем прикреплённые файлы
      state.pendingFiles = [];
      if (els.fileInput) els.fileInput.value = "";
      updateAttachPlaceholder();
      if (state.chat) await state.chat.newChat();
    };

    els.newChatBtn.addEventListener("click", doNewChat);
    if (els.newChatTopBtn) {
      els.newChatTopBtn.addEventListener("click", doNewChat);
    }
    els.refreshTabsBtn.addEventListener("click", () => { refreshAll().catch(() => {}); });

    if (els.changeServerBtn) {
      els.changeServerBtn.addEventListener("click", () => {
        state.isChangingServer = true;
        UI.show(els.connectScreen);
        els.connectUrl.focus();
      });
    }
  }

  // ----------------------------------------------------------
  //  Bootstrap
  // ----------------------------------------------------------
  function init() {
    initConnectScreen();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();