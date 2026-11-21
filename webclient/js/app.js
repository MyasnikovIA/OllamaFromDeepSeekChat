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
    logoutBtn:      document.getElementById("logout-btn"),          // ← НОВОЕ
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
    pendingFilesList: document.getElementById("pending-files-list"),
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
    pendingFiles: [], // { id, file, sent }
  };

  // ← НОВОЕ: защита от двойной привязки обработчиков
  //            (после disconnect → enterChat без перезагрузки страницы)
  let bindingsInitialized = false;

  // ----------------------------------------------------------
  //  Connect screen
  // ----------------------------------------------------------
  function initConnectScreen() {
    if (window.DSConfig.hasSavedUrl()) {
      const saved = window.DSConfig.getBaseUrl();
      els.connectUrl.value = saved;
      autoConnect(saved);
    } else {
      els.connectUrl.value = window.DSConfig.DEFAULT_BASE_URL;
      UI.show(els.connectScreen);
      setTimeout(() => { try { els.connectUrl.focus(); } catch (_) {} }, 50);
    }
    els.connectBtn.addEventListener("click", connect);
    els.connectUrl.addEventListener("keydown", (e) => {
      if (e.key === "Enter") connect();
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
      setTimeout(() => { try { els.connectUrl.focus(); } catch (_) {} }, 50);
    }
  }

  async function connect() {
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
      await enterChat();
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
  //  disconnect: остановка polling, abort стримов, очистка UI/state
  //  clearSaved = true  → logout (стираем сохранённый URL)
  //  clearSaved = false → change server (URL оставляем в поле ввода)
  // ----------------------------------------------------------
  function disconnect(clearSaved) {
    // 1) остановить polling
    if (state.pollTimer) {
      clearInterval(state.pollTimer);
      state.pollTimer = null;
    }

    // 2) прервать активные стримы всех вкладок
    for (const tabId in state.tabControllers) {
      const ctrl = state.tabControllers[tabId];
      if (!ctrl) continue;
      try { if (ctrl.currentStream) ctrl.currentStream.abort(); } catch (_) {}
      try {
        if (typeof ctrl._stopWatchdog === "function") ctrl._stopWatchdog();
      } catch (_) {}
    }

    // 3) очистить UI
    els.messagesContainer.innerHTML = "";
    els.tabsList.innerHTML = "";
    els.browsersList.innerHTML = "";
    els.composer.value = "";
    els.composer.style.height = "auto";
    els.pendingFilesList.innerHTML = "";
    els.pendingFilesList.classList.add("hidden");
    els.thinkToggle.checked = false;
    els.searchToggle.checked = false;

    // 4) сбросить state
    state.api = null;
    state.activeTabId = null;
    state.tabs = [];
    state.browsers = [];
    state.modes = { thinking: false, smartSearch: false };
    state.chat = null;
    state.tabContainers = {};
    state.tabControllers = {};
    state.pendingFiles = [];

    // 5) URL в поле ввода
    if (clearSaved) {
      window.DSConfig.clearBaseUrl();
      els.connectUrl.value = window.DSConfig.DEFAULT_BASE_URL;
    } else {
      els.connectUrl.value = window.DSConfig.getBaseUrl();
    }

    // 6) показать экран подключения
    UI.hide(els.chatScreen);
    UI.show(els.connectScreen);
    els.connectStatus.textContent = "";
    els.connectStatus.classList.remove("error", "success");
    els.connectBtn.disabled = false;

    setTimeout(() => { try { els.connectUrl.focus(); } catch (_) {} }, 50);
  }

  // ----------------------------------------------------------
  //  enterChat: показ чата + одноразовая привязка обработчиков
  // ----------------------------------------------------------
  async function enterChat() {
    UI.hide(els.connectScreen);
    UI.show(els.chatScreen);

    if (!bindingsInitialized) {
      bindChatEvents();
      bindModes();
      bindSidebar();
      bindAttach();
      bindingsInitialized = true;
    }

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

  async function refreshAll() {
    if (!state.api) return;
    try {
      const tabs = await state.api.tabs();
      state.tabs = tabs;

      // Автовыбор первой вкладки, если текущая не установлена
      if (!state.activeTabId) {
        const selected = tabs.find(t => t.selected) || tabs[0];
        if (selected) state.activeTabId = selected.tabId;
      }
      // Если текущая вкладка исчезла, берём первую
      if (state.activeTabId && !tabs.some(t => t.tabId === state.activeTabId)) {
        state.activeTabId = tabs[0] ? tabs[0].tabId : null;
      }

      // Синхронизация выделения на сервере
      if (state.activeTabId) {
        const current = tabs.find(t => t.tabId === state.activeTabId);
        if (current && !current.selected) {
          try { await state.api.selectTab(state.activeTabId); } catch (_) {}
        }
      }

      // Получение режимов
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

      if (state.activeTabId) {
        switchTabUI(state.activeTabId);
      }
    } catch (e) {
      renderStatus(false, e.message);
    }
  }

  async function refreshBrowsers() {
    if (!state.api) return;
    try { state.browsers = await state.api.browsers(); renderBrowsers(); }
    catch (e) { console.warn("Failed to fetch browsers:", e); }
  }

  function renderTabs() {
    UI.renderTabs(els.tabsList, state.tabs, state.activeTabId, selectAndRefresh, async (tabId) => {
      try {
        await state.api.cmd("closeTab", { tabId });
        UI.removeTabUI(tabId, state.tabContainers);
        delete state.tabControllers[tabId];
        if (state.activeTabId === tabId) {
          state.activeTabId = null;
          els.messagesContainer.innerHTML = "";
        }
        state.tabs = state.tabs.filter(t => t.tabId !== tabId);
        renderTabs();
      } catch (e) { alert("Ошибка закрытия вкладки: " + e.message); }
    });

    if (state.activeTabId) {
      const t = state.tabs.find(x => x.tabId === state.activeTabId);
      if (t) {
        const displayInfo = UI.getTabDisplayInfo(state.activeTabId, state.tabs, state.browsers);
        els.chatSubtitle.textContent = displayInfo;
      }
    }
  }

  function renderBrowsers() {
    UI.renderBrowsers(els.browsersList, state.browsers, async (index) => {
      try { await state.api.openBrowser(index); } catch (e) { alert(e.message); }
    });
  }

  function renderModes() {
    UI.setModeCheckboxes(els.thinkToggle, els.searchToggle, state.modes);
  }

  function renderStatus(ok, detail) {
    UI.setStatus(els.statusPill, ok, ok ? "Подключено" : ("Нет связи: " + (detail || "")));
  }

  async function selectAndRefresh(tabId) {
    if (tabId !== state.activeTabId) {
      state.pendingFiles = [];
      renderPendingFiles();
    }
    state.activeTabId = tabId;
    try { await state.api.selectTab(tabId); } catch (_) {}
    renderTabs();
    switchTabUI(tabId);
  }

  function switchTabUI(tabId) {
    for (const id in state.tabContainers) state.tabContainers[id].classList.add("hidden");

    if (!state.tabContainers[tabId]) {
      const div = document.createElement("div");
      div.className = "messages";
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.innerHTML = `<div class="empty-icon">💬</div><div class="empty-title">Начните диалог</div>`;
      div.appendChild(empty);
      els.messagesContainer.appendChild(div);

      state.tabContainers[tabId] = div;
      state.tabControllers[tabId] = new window.ChatController(state.api, {
        messages: div, emptyState: empty, stopBtn: els.stopBtn, composer: els.composer,
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
    els.stopBtn.addEventListener("click", async () => { if (state.chat) await state.chat.stop(); });
  }

  async function sendCurrent() {
    const text = els.composer.value.trim();
    const unsentFiles = state.pendingFiles
        .filter(f => !f.sent)
        .map(f => f.file);

    if (!text && unsentFiles.length === 0) return;
    if (!state.chat) return;

    els.composer.value = "";
    els.composer.style.height = "auto";

    try {
      await state.chat.send(text, unsentFiles);
      state.pendingFiles = [];
      renderPendingFiles();
    } catch (e) {
      console.error("send failed:", e);
      state.pendingFiles = state.pendingFiles.map(f => ({ ...f, sent: false }));
      renderPendingFiles();
    }
  }

  // ----------------------------------------------------------
  //  Прикрепление + UI чипы
  // ----------------------------------------------------------
  function bindAttach() {
    if (!els.attachBtn || !els.fileInput) return;
    els.attachBtn.addEventListener("click", () => els.fileInput.click());
    els.fileInput.addEventListener("change", () => {
      const picked = Array.from(els.fileInput.files || []);
      picked.forEach(f => {
        state.pendingFiles.push({ id: Date.now() + Math.random().toString(), file: f, sent: false });
      });
      els.fileInput.value = "";
      renderPendingFiles();
    });
  }

  function renderPendingFiles() {
    if (!els.pendingFilesList) return;
    els.pendingFilesList.innerHTML = "";
    if (state.pendingFiles.length === 0) {
      els.pendingFilesList.classList.add("hidden");
      return;
    }
    els.pendingFilesList.classList.remove("hidden");

    state.pendingFiles.forEach(item => {
      const chip = document.createElement("div");
      chip.className = "pending-file-chip" + (item.sent ? " sent" : "");

      const name = document.createElement("span");
      name.className = "file-name";
      name.textContent = item.file.name;

      const actions = document.createElement("div");
      actions.className = "file-actions";

      if (item.sent) {
        const ok = document.createElement("span");
        ok.className = "sent-mark";
        ok.textContent = "✓";
        actions.appendChild(ok);
      } else {
        const sendBtn = document.createElement("button");
        sendBtn.className = "btn-chip-send";
        sendBtn.textContent = "↩";
        sendBtn.title = "Вставить этот файл в DeepSeek вручную";
        sendBtn.onclick = async () => {
          if (!state.chat) return;
          sendBtn.disabled = true;
          sendBtn.textContent = "…";
          try {
            await state.chat.uploadFileToPlugin(item.file);
            item.sent = true;
            renderPendingFiles();
          } catch (e) {
            console.error("uploadFileToPlugin failed:", e);
            sendBtn.disabled = false;
            sendBtn.textContent = "↩";
          }
        };
        actions.appendChild(sendBtn);
      }

      const removeBtn = document.createElement("button");
      removeBtn.className = "btn-chip-remove";
      removeBtn.textContent = "✕";
      removeBtn.onclick = () => {
        state.pendingFiles = state.pendingFiles.filter(f => f.id !== item.id);
        renderPendingFiles();
      };
      actions.appendChild(removeBtn);

      chip.appendChild(name);
      chip.appendChild(actions);
      els.pendingFilesList.appendChild(chip);
    });
  }

  // ----------------------------------------------------------
  //  Режимы / Sidebar
  // ----------------------------------------------------------
  function bindModes() {
    els.thinkToggle.addEventListener("change", async () => {
      if (!state.api) return;
      const on = els.thinkToggle.checked;
      try { await state.api.setThinking(on); state.modes.thinking = on; }
      catch (e) { els.thinkToggle.checked = !on; }
    });
    els.searchToggle.addEventListener("change", async () => {
      if (!state.api) return;
      const on = els.searchToggle.checked;
      try { await state.api.setSearch(on); state.modes.smartSearch = on; }
      catch (e) { els.searchToggle.checked = !on; }
    });
  }

  function bindSidebar() {
    const doNewChat = async () => {
      state.pendingFiles = [];
      renderPendingFiles();
      if (state.chat) await state.chat.newChat();
    };
    els.newChatBtn.addEventListener("click", doNewChat);
    if (els.newChatTopBtn) els.newChatTopBtn.addEventListener("click", doNewChat);
    els.refreshTabsBtn.addEventListener("click", () => { refreshAll().catch(() => {}); });

    // ← НОВОЕ: смена сервера (сохраняем URL) и logout (стираем URL)
    if (els.changeServerBtn) {
      els.changeServerBtn.addEventListener("click", () => disconnect(false));
    }
    if (els.logoutBtn) {
      els.logoutBtn.addEventListener("click", () => disconnect(true));
    }
  }

  function init() { initConnectScreen(); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();