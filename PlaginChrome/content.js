(() => {
  "use strict";

  // ============================================================
  //  НОВОЕ: guard — регистрируемся ТОЛЬКО на chat.deepseek.com.
  //  Скрипт может быть внедрён и на других страницах, если
  //  matches в manifest.json слишком широкий; на любой
  //  странице вне chat.deepseek.com молча выходим, не
  //  регистрируемся, не шлём /log, не открываем SSE.
  // ============================================================
  const DS_HOST = "chat.deepseek.com";

  function isDeepSeekPage() {
    try {
      return location.protocol === "https:"
          && location.hostname === DS_HOST;
    } catch (_) {
      return false;
    }
  }

  if (!isDeepSeekPage()) return;              // не наш URL — до свидания
  if (window.top !== window) return;          // не регистрируемся из iframe

  const CHANNEL    = "__ds_stream_chunk__";
  const EVT_EXEC   = "__ds_execute_cmd__";
  const EVT_CONSOLE= "__ds_console__";
  const EVT_RESULT = "__ds_eval_result__";
  const EVT_READY  = "__ds_injected_ready__";
  const TAG        = "[DS-INTERCEPT]";

  const TAB_ID = "tab-" + (
      (typeof crypto !== "undefined" && crypto.randomUUID)
          ? crypto.randomUUID()
          : (Date.now() + "-" + Math.random().toString(36).slice(2, 10))
  );
  const EVT_MODE_STATE  = "__ds_mode_state__";
  const EVT_TOKEN_LIMIT = "__ds_token_limit__";

  const SERVER     = "http://127.0.0.1:11434";
  const RECONNECT_MS = 5000;

  let postQueue = Promise.resolve();
  function postLog(level, text, streamId, streamType) {
    // НОВОЕ: не шлём логи с неверного URL
    if (!isDeepSeekPage()) return postQueue;
    const payload = { level, text, tabId: TAB_ID };
    if (streamId) payload.streamId = streamId;
    if (streamType) payload.streamType = streamType;
    const body = JSON.stringify(payload);
    postQueue = postQueue.then(() =>
        fetch(SERVER + "/log", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body
        }).catch(() => {})
    );
    return postQueue;
  }

  // ============================================================
  //  НОВОЕ: надёжная отправка tab-op лога даже при закрытии вкладки
  // ============================================================
  function postTabOp(text) {
    // НОВОЕ: не шлём tab-op с неверного URL
    if (!isDeepSeekPage()) return;
    try {
      const body = JSON.stringify({ level: "tab-op", text, tabId: TAB_ID });
      if (navigator.sendBeacon) {
        const ok = navigator.sendBeacon(
            SERVER + "/log",
            new Blob([body], { type: "application/json" })
        );
        if (ok) return;
      }
      fetch(SERVER + "/log", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
        keepalive: true
      }).catch(() => {});
    } catch (_) {}
  }

  // ---------- уведомление сервера о закрытии вкладки ----------
  // sendBye НЕ оборачиваем в isDeepSeekPage(): он вызывается ровно
  // в момент выгрузки страницы, когда URL ещё старый. Нам важно,
  // чтобы сервер снял регистрацию.
  let byeSent = false;
  function sendBye(reason) {
    if (byeSent) return;
    byeSent = true;
    try {
      const body = JSON.stringify({
        level:  "bye",
        tabId:  TAB_ID,
        reason: reason || "pagehide",
        text:   reason || "pagehide"
      });
      if (navigator.sendBeacon) {
        const ok = navigator.sendBeacon(
            SERVER + "/log",
            new Blob([body], { type: "application/json" })
        );
        if (ok) return;
      }
      fetch(SERVER + "/log", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body,
        keepalive: true
      }).catch(() => {});
    } catch (_) {}
  }

  window.addEventListener("pagehide", (e) => {
    sendBye(e && e.persisted ? "pagehide-persisted" : "pagehide");
  }, { capture: true });

  window.addEventListener("beforeunload", () => {
    sendBye("beforeunload");
  }, { capture: true });

  window.addEventListener("unload", () => {
    sendBye("unload");
  }, { capture: true });

  // ---------- стримы (без изменений) ----------
  const streams = new Map();

  function getStream(streamId) {
    let s = streams.get(streamId);
    if (!s) {
      s = {
        lastFragmentId: 0,
        currentFragmentType: null,
        buffer: "",
        bufferType: null,
        flushTimer: null
      };
      streams.set(streamId, s);
    }
    return s;
  }

  function flushStream(streamId) {
    const s = streams.get(streamId);
    if (!s || !s.buffer) return;
    const t = s.buffer;
    const type = s.bufferType || "RESPONSE";
    s.buffer = "";
    s.bufferType = null;
    postLog("stream", t, streamId, type);
  }

  function scheduleFlush(streamId) {
    const s = streams.get(streamId);
    if (!s || s.flushTimer) return;
    s.flushTimer = setTimeout(() => {
      if (!s.flushTimer) return;
      s.flushTimer = null;
      flushStream(streamId);
    }, 100);
  }

  function processFragmentBatch(frags, state) {
    const events = [];
    if (!Array.isArray(frags)) return events;
    for (const frag of frags) {
      if (!frag || typeof frag.id !== "number") continue;
      if (frag.id <= (state.lastFragmentId || 0)) continue;
      const type = frag.type || "RESPONSE";
      const content = typeof frag.content === "string" ? frag.content : "";
      if (content) events.push({ type, text: content });
      state.lastFragmentId = frag.id;
      state.currentFragmentType = type;
    }
    return events;
  }

  function extractEvents(obj, state) {
    const events = [];
    if (!obj) return events;

    if (obj.p === "response/status" || obj.p === "response/error") return events;
    if (obj.p === "response" && obj.o === "BATCH") return events;
    if (obj.p && (obj.p.endsWith("/elapsed_secs") || obj.p.endsWith("/references"))) return events;

    if (obj.v && typeof obj.v === "object" && !Array.isArray(obj.v) && obj.v.response) {
      return processFragmentBatch(obj.v.response.fragments, state);
    }

    if (obj.p === "response/fragments" && Array.isArray(obj.v)) {
      return processFragmentBatch(obj.v, state);
    }

    if (typeof obj.v === "string") {
      if (obj.p && !obj.p.endsWith("/content")) return events;
      if (!state.currentFragmentType) return events;
      events.push({ type: state.currentFragmentType, text: obj.v });
    }

    return events;
  }

  window.addEventListener(CHANNEL, (evt) => {
    const { chunk = "", phase = "chunk", streamId = "" } = evt.detail || {};

    if (phase === "done") {
      const s = streams.get(streamId);
      if (s) {
        if (s.flushTimer) { clearTimeout(s.flushTimer); s.flushTimer = null; }
        if (s.buffer && s.bufferType) {
          postLog("stream", s.buffer, streamId, s.bufferType);
          s.buffer = "";
          s.bufferType = null;
        }
        streams.delete(streamId);
      }
      postLog("stream-done", "", streamId);
      console.info(`${TAG} stream finished: ${streamId}`);
      return;
    }

    const s = getStream(streamId);

    try {
      const payload = chunk.replace(/^data:\s*/, "").trim();
      if (payload && payload !== "[DONE]") {
        const obj = JSON.parse(payload);
        const events = extractEvents(obj, s);
        for (const e of events) {
          if (!e.text) continue;
          if (s.bufferType && s.bufferType !== e.type) {
            if (s.flushTimer) { clearTimeout(s.flushTimer); s.flushTimer = null; }
            flushStream(streamId);
          }
          s.bufferType = e.type;
          s.buffer += e.text;
          scheduleFlush(streamId);
        }
      }
    } catch (_) {}

    try {
      chrome.runtime.sendMessage({
        type: "ds_chunk", chunk, phase, streamId
      }).catch(() => {});
    } catch (_) {}
  });

  window.addEventListener(EVT_CONSOLE, (evt) => {
    const { level = "log", text = "" } = evt.detail || {};
    if (!text || !text.trim()) return;
    postLog(level, text);
  });

  window.addEventListener(EVT_RESULT, (evt) => {
    const { ok = true, text = "" } = evt.detail || {};
    postLog(ok ? "result" : "error", text);
  });

  window.addEventListener(EVT_MODE_STATE, (evt) => {
    const { thinking = false, smartSearch = false } = evt.detail || {};
    postLog("mode-state", JSON.stringify({
      thinking: !!thinking, smartSearch: !!smartSearch
    }));
  });
  window.addEventListener(EVT_TOKEN_LIMIT, (evt) => {
    const { resetOn = "" } = evt.detail || {};
    console.warn(`${TAG} token limit detected, reset:`, resetOn);
    postLog("token-limit", resetOn);
  });

  let ready = false;
  const pendingCmds = [];

  function flushPending() {
    ready = true;
    while (pendingCmds.length) dispatchToMain(pendingCmds.shift());
  }

  function dispatchToMain(data) {
    try {
      window.dispatchEvent(new CustomEvent(EVT_EXEC, { detail: data }));
    } catch (_) {}
  }

  window.addEventListener(EVT_READY, flushPending);
  setTimeout(() => { if (!ready) flushPending(); }, 1000);

  // ============================================================
  //  НОВОЕ: регистрация с background для управления вкладками
  //  (только если мы всё ещё на chat.deepseek.com)
  // ============================================================
  function registerWithBackground() {
    if (!isDeepSeekPage()) return;
    try {
      chrome.runtime.sendMessage(
          { type: "register_tab", tabId: TAB_ID },
          (_resp) => { /* lastError игнорируем */ }
      );
    } catch (_) {}
  }
  registerWithBackground();
  // service worker в MV3 может «засыпать» — периодически напоминаем о себе
  setInterval(registerWithBackground, 30000);

  // ============================================================
  //  НОВОЕ: обработчик команд управления вкладками
  // ============================================================
  function handleTabCommand(cmd) {
    // НОВОЕ: если вдруг URL сменился (SPA-навигация), команды
    // управления вкладками всё равно шлём только с deepseek
    if (!isDeepSeekPage()) return;

    const type = cmd && cmd.type;

    if (type === "openTab") {
      const url = (cmd.url && String(cmd.url).trim()) || "https://chat.deepseek.com/";
      const active = cmd.active !== false;
      try {
        chrome.runtime.sendMessage({ type: "open_tab", url, active }, (resp) => {
          if (chrome.runtime.lastError) {
            postTabOp("openTab failed: " + chrome.runtime.lastError.message);
            return;
          }
          if (resp && resp.ok) {
            postTabOp("opened tab (chrome " + resp.chromeTabId + "): " + url);
          } else {
            postTabOp("openTab error: " + (resp && resp.error || "unknown"));
          }
        });
      } catch (e) {
        postTabOp("openTab exception: " + e.message);
      }
      return;
    }

    if (type === "closeTab") {
      const target = cmd.tabId || "";
      if (!target) {
        postTabOp("closeTab: empty tabId");
        return;
      }
      // Сразу сообщаем Java (если закрываем себя — успеем до unload)
      postTabOp("closing: " + target);
      try {
        chrome.runtime.sendMessage({ type: "close_tab", tabId: target }, (resp) => {
          if (chrome.runtime.lastError) return; // себя закрыли — колбэк может не сработать
          if (resp && resp.ok) {
            // успешное закрытие уже отрапортовано выше
          } else {
            postTabOp("closeTab error: " + (resp && resp.error || "unknown"));
          }
        });
      } catch (e) {
        postTabOp("closeTab exception: " + e.message);
      }
      return;
    }

    if (type === "closeAllTabs") {
      postTabOp("closing all deepseek tabs...");
      try {
        chrome.runtime.sendMessage({ type: "close_all_deepseek" }, (resp) => {
          if (chrome.runtime.lastError) return;
          if (resp && resp.ok) {
            postTabOp("closed " + (resp.closed || 0) + " deepseek tab(s)");
          } else {
            postTabOp("closeAllTabs error: " + (resp && resp.error || "unknown"));
          }
        });
      } catch (e) {
        postTabOp("closeAllTabs exception: " + e.message);
      }
      return;
    }
  }

  // ---------- SSE ----------
  let es = null;
  let reconnectTimer = null;

  function registerTab() {
    postLog("hello", JSON.stringify({
      url: location.href,
      ua: navigator.userAgent
    }));
    dispatchToMain({ type: "getState" });
  }
  function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connectSSE();
    }, RECONNECT_MS);
  }

  function connectSSE() {
    // НОВОЕ: если URL внезапно стал не-deepseek (SPA-навигация / уход со страницы),
    // не открываем EventSource
    if (!isDeepSeekPage()) return;

    if (es) {
      try { es.close(); } catch (_) {}
      es = null;
    }

    try {
      es = new EventSource(SERVER + "/events?tabId=" + encodeURIComponent(TAB_ID));
    } catch (_) {
      scheduleReconnect();
      return;
    }

    es.onopen = () => {
      console.info(`${TAG} EventSource connected, tabId=${TAB_ID}`);
      registerTab();
    };

    es.onerror = () => {
      try { es.close(); } catch (_) {}
      es = null;
      scheduleReconnect();
    };

    es.onmessage = (e) => {
      let data = e.data;
      try { data = JSON.parse(data); } catch (_) {}

      // НОВОЕ: команды управления вкладками обрабатываем сразу,
      // не дожидаясь готовности injected.js и не передавая в него
      if (data && typeof data === "object" && (
          data.type === "openTab" ||
          data.type === "closeTab" ||
          data.type === "closeAllTabs"
      )) {
        handleTabCommand(data);
        return;
      }

      if (!ready) { pendingCmds.push(data); return; }
      dispatchToMain(data);
    };
  }

  connectSSE();

  console.info(`${TAG} listener installed, tabId=${TAB_ID}`);
})();

