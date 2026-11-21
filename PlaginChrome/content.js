(() => {
  "use strict";

  const DS_HOST = "chat.deepseek.com";

  function isDeepSeekPage() {
    try {
      return location.protocol === "https:"
          && location.hostname === DS_HOST;
    } catch (_) {
      return false;
    }
  }

  if (!isDeepSeekPage()) return;
  if (window.top !== window) return;

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

  function postTabOp(text) {
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

  // ---------- стримы ----------
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
    postLog(level === "error" ? "console-error" : level, text);
  });

  // ============================================================
  //  Прямое чтение режимов из DOM (без участия injected.js).
  //
  //  content-script имеет доступ к document, поэтому может сам
  //  найти тогглы DeepThink / Search по aria-pressed и отправить
  //  mode-state на сервер. Это работает, даже если injected.js
  //  никогда не диспатчит EVT_MODE_STATE.
  // ============================================================
  function findThinkingToggleInDom() {
    for (const el of document.querySelectorAll("[aria-pressed]")) {
      const text = (el.textContent || "").trim();
      if (/Глубокое мышление/i.test(text)
          || /Deep\s*Think/i.test(text)
          || /^R1$/i.test(text)) {
        return el;
      }
      const title = el.getAttribute("title") || el.getAttribute("aria-label") || "";
      if (/Глубокое мышление/i.test(title) || /Deep\s*Think/i.test(title)) {
        return el;
      }
    }
    for (const el of document.querySelectorAll("[aria-pressed]")) {
      for (const p of el.querySelectorAll("svg path")) {
        const d = p.getAttribute("d") || "";
        if (d.startsWith("M8.00192 6.64454")) return el;
      }
    }
    return null;
  }

  function findSmartSearchToggleInDom() {
    for (const el of document.querySelectorAll("[aria-pressed]")) {
      const spans = el.querySelectorAll("span");
      for (const s of spans) {
        const t = (s.textContent || "").trim();
        if (/^(Search|Умный поиск)$/i.test(t)) return el;
      }
      const title = el.getAttribute("title") || el.getAttribute("aria-label") || "";
      if (/^(Search|Умный поиск)$/i.test(title)) return el;
    }
    for (const el of document.querySelectorAll("[aria-pressed]")) {
      let hasSearchHandle = false;
      let isThinking = false;
      for (const p of el.querySelectorAll("svg path")) {
        const d = p.getAttribute("d") || "";
        if (d.includes("M1.6399999856948853,8 C1.6399999856948853,8 14.359999656677246,8 14.359999656677246,8")) {
          hasSearchHandle = true;
        }
        if (d.startsWith("M8.00192 6.64454")) {
          isThinking = true;
        }
      }
      if (hasSearchHandle && !isThinking) return el;
    }
    return null;
  }

  function readModesFromDom() {
    const thinkEl  = findThinkingToggleInDom();
    const searchEl = findSmartSearchToggleInDom();
    return {
      thinking:    !!(thinkEl  && thinkEl.getAttribute("aria-pressed") === "true"),
      smartSearch: !!(searchEl && searchEl.getAttribute("aria-pressed") === "true")
    };
  }

  let __lastModeState = { thinking: null, smartSearch: null };

  function reportModes(force) {
    let m;
    try { m = readModesFromDom(); } catch (_) { return; }
    if (!force
        && m.thinking === __lastModeState.thinking
        && m.smartSearch === __lastModeState.smartSearch) {
      return;
    }
    __lastModeState = m;
    postLog("mode-state", JSON.stringify(m));
  }

  try {
    const obs = new MutationObserver(() => reportModes(false));
    obs.observe(document.documentElement || document, {
      childList: true, subtree: true, attributes: true,
      attributeFilter: ["aria-pressed", "class"]
    });
  } catch (_) {}
  setTimeout(() => reportModes(true), 1500);
  setInterval(() => reportModes(false), 2000);

  window.addEventListener(EVT_RESULT, (evt) => {
    const { ok = true, text = "" } = evt.detail || {};
    postLog(ok ? "result" : "error", text);
    // после любой команды синхронизировать режимы с сервером
    if (ok) setTimeout(() => reportModes(true), 200);
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
  setInterval(registerWithBackground, 30000);

  function handleTabCommand(cmd) {
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
      postTabOp("closing: " + target);
      try {
        chrome.runtime.sendMessage({ type: "close_tab", tabId: target }, (resp) => {
          if (chrome.runtime.lastError) return;
          if (resp && resp.ok) {
            // уже отрапортовано выше
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

  let es = null;
  let reconnectTimer = null;

  function registerTab() {
    postLog("hello", JSON.stringify({
      url: location.href,
      ua: navigator.userAgent
    }));
    dispatchToMain({ type: "getState" });
    // принудительный снимок режимов сразу после подключения
    setTimeout(() => reportModes(true), 500);
  }
  function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connectSSE();
    }, RECONNECT_MS);
  }

  function connectSSE() {
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