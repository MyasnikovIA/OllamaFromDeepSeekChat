(() => {
  "use strict";

  if (window.__dsStreamPatched__) return;
  window.__dsStreamPatched__ = true;

  const TARGET_PATH  = "/api/v0/chat/completion";
  const CHANNEL      = "__ds_stream_chunk__";
  const EVT_EXEC     = "__ds_execute_cmd__";
  const EVT_CONSOLE  = "__ds_console__";
  const EVT_RESULT   = "__ds_eval_result__";
  const EVT_READY    = "__ds_injected_ready__";

  let __streamSeq = 0;
  const nextStreamId = () =>
      "s" + Date.now() + "-" + (++__streamSeq) + "-" +
      Math.random().toString(36).slice(2, 8);

  const emit = (chunk, phase = "chunk", streamId = "") => {
    try {
      window.dispatchEvent(
          new CustomEvent(CHANNEL, { detail: { chunk, phase, streamId } })
      );
    } catch (_) {}
  };
  const emitConsole = (level, text) => {
    try {
      window.dispatchEvent(new CustomEvent(EVT_CONSOLE, { detail: { level, text } }));
    } catch (_) {}
  };
  const emitResult = (ok, text) => {
    try {
      window.dispatchEvent(new CustomEvent(EVT_RESULT, { detail: { ok, text } }));
    } catch (_) {}
  };

  const safeStringify = (v) => {
    try {
      if (v === undefined) return "undefined";
      if (v === null) return "null";
      if (typeof v === "function") return "[Function " + (v.name || "anonymous") + "]";
      if (typeof v === "object") return JSON.stringify(v);
      return String(v);
    } catch { return String(v); }
  };

  const stringifyArg = (a) => {
    if (typeof a === "string") return a;
    if (a instanceof Error) return a.stack || a.message;
    try { return JSON.stringify(a); } catch { return String(a); }
  };

  // ИЗМЕНЕНО: добавлен "[Captcha]" чтобы не спамить в логи
  const SKIP_PREFIXES = ["[DS-INTERCEPT]", "[DS] ", "[Captcha] "];

  ["log", "info", "warn", "error", "debug"].forEach((level) => {
    const orig = console[level].bind(console);
    console[level] = function (...args) {
      let text = "";
      try { text = args.map(stringifyArg).join(" "); } catch (_) { text = ""; }
      if (text && text.trim() !== "") {
        const skip = SKIP_PREFIXES.some((p) => text.startsWith(p));
        if (!skip) {
          try { emitConsole(level, text); } catch (_) {}
        }
      }
      return orig(...args);
    };
  });

  // ---------- fetch/XHR patch ----------
  const isTargetUrl = (url) => {
    if (!url) return false;
    try {
      const u = new URL(url, location.href);
      return u.hostname === "chat.deepseek.com" && u.pathname === TARGET_PATH;
    } catch { return false; }
  };

  const origFetch = window.fetch;
  window.fetch = async function (...args) {
    const req = args[0];
    const url = typeof req === "string" ? req : (req && req.url) || "";

    const response = await origFetch.apply(this, args);
    if (!isTargetUrl(url)) return response;

    let cloned;
    try { cloned = response.clone(); }
    catch (e) { console.warn("[DS-INTERCEPT] clone failed:", e); return response; }

    const streamId = nextStreamId();

    (async () => {
      const reader = cloned.body && cloned.body.getReader();
      if (!reader) { emit("", "done", streamId); return; }
      const decoder = new TextDecoder("utf-8");
      let buffer = "";
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let idx;
          while ((idx = buffer.indexOf("\n\n")) !== -1) {
            const event = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 2);
            for (const line of event.split("\n")) {
              if (line.startsWith("data:")) emit(line, "sse", streamId);
            }
          }
        }
        if (buffer.trim()) {
          for (const line of buffer.split("\n")) {
            if (line.startsWith("data:")) emit(line, "sse", streamId);
          }
        }
      } catch (err) {
        console.warn("[DS-INTERCEPT] stream error:", err);
      } finally {
        emit("", "done", streamId);
      }
    })();

    return response;
  };

  const OrigXHR = window.XMLHttpRequest;
  function PatchedXHR() {
    const xhr = new OrigXHR();
    let target = false;
    let offset = 0;
    let streamId = null;
    const origOpen = xhr.open;

    xhr.open = function (method, url, ...rest) {
      try {
        const u = new URL(url, location.href);
        target = u.hostname === "chat.deepseek.com" && u.pathname === TARGET_PATH;
        if (target) streamId = nextStreamId();
      } catch { target = false; streamId = null; }
      return origOpen.call(this, method, url, ...rest);
    };

    xhr.addEventListener("progress", () => {
      if (!target || !streamId) return;
      if (xhr.responseType && xhr.responseType !== "text") return;
      const text = xhr.responseText || "";
      if (text.length <= offset) return;
      const fresh = text.slice(offset);
      offset = text.length;
      for (const line of fresh.split("\n")) {
        if (line.startsWith("data:")) emit(line, "sse", streamId);
      }
    });

    xhr.addEventListener("loadend", () => {
      if (target && streamId) emit("", "done", streamId);
    });

    return xhr;
  }
  PatchedXHR.prototype = OrigXHR.prototype;
  window.XMLHttpRequest = PatchedXHR;

  // ---------- общий helper: клик мышью с координатами ----------
  function clickWithMouse(el) {
    if (!el) throw new Error("element is null");
    el.scrollIntoView({ block: "center", inline: "center" });
    const rect = el.getBoundingClientRect();
    const opts = {
      bubbles: true, cancelable: true, view: window,
      clientX: rect.left + rect.width / 2,
      clientY: rect.top + rect.height / 2,
      button: 0, buttons: 1
    };
    el.dispatchEvent(new MouseEvent("mousedown", opts));
    el.dispatchEvent(new MouseEvent("mouseup", opts));
    el.dispatchEvent(new MouseEvent("click", opts));
  }

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // ИЗМЕНЕНО: Функция ожидания появления элемента на странице (до 10 секунд)
  async function waitForElement(selector, timeout = 10000) {
    const start = Date.now();
    while (Date.now() - start < timeout) {
      const el = document.querySelector(selector);
      if (el) return el;
      await sleep(100);
    }
    return null;
  }

  // ---------- sendToDeepSeek ----------
  async function sendToDeepSeek(text) {
    // Ждем загрузки поля ввода
    const input = await waitForElement('[placeholder="Сообщение для DeepSeek"]');
    if (!input) throw new Error("[DS] Поле ввода не найдено");

    const rect = input.getBoundingClientRect();
    const mouseOpts = {
      bubbles: true, cancelable: true, view: window,
      clientX: rect.left + rect.width / 2,
      clientY: rect.top + rect.height / 2,
      button: 0, buttons: 1
    };
    input.dispatchEvent(new MouseEvent("mousedown", mouseOpts));
    input.dispatchEvent(new MouseEvent("mouseup", mouseOpts));
    input.dispatchEvent(new MouseEvent("click", mouseOpts));
    input.focus();

    await sleep(50);

    if (input.isContentEditable) {
      const range = document.createRange();
      range.selectNodeContents(input);
      range.collapse(false);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);

      const ok = document.execCommand("insertText", false, text);
      if (!ok) {
        input.textContent = text;
        input.dispatchEvent(new InputEvent("input", {
          bubbles: true, data: text, inputType: "insertText"
        }));
      }
    } else if (input.tagName === "TEXTAREA" || input.tagName === "INPUT") {
      const proto = input.tagName === "TEXTAREA"
          ? HTMLTextAreaElement.prototype
          : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, "value").set.call(input, text);
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    } else {
      throw new Error("[DS] Неизвестный тип поля ввода");
    }

    await sleep(100);

    // ИЗМЕНЕНО: Ждем, пока кнопка отправки станет активной (до 5 секунд)
    let sendBtn = null;
    const btnStartTime = Date.now();
    while (Date.now() - btnStartTime < 5000) {
      const icons = document.querySelectorAll(".ds-button__icon.ds-button__icon--last-child");
      for (const icon of icons) {
        const path = icon.querySelector("svg path");
        if (path && /M8\.3125\s+0\.980206/.test(path.getAttribute("d") || "")) {
          sendBtn =
              icon.closest(".ds-button") ||
              icon.closest("button") ||
              icon.parentElement ||
              icon;
          break;
        }
      }
      if (sendBtn) break;
      await sleep(100);
    }

    if (!sendBtn) throw new Error("[DS] Кнопка отправки не найдена");
    if (sendBtn.disabled || sendBtn.getAttribute("aria-disabled") === "true") {
      throw new Error("[DS] Кнопка отправки заблокирована");
    }

    const bRect = sendBtn.getBoundingClientRect();
    const bOpts = {
      bubbles: true, cancelable: true, view: window,
      clientX: bRect.left + bRect.width / 2,
      clientY: bRect.top + bRect.height / 2,
      button: 0, buttons: 1
    };
    sendBtn.dispatchEvent(new MouseEvent("mousedown", bOpts));
    sendBtn.dispatchEvent(new MouseEvent("mouseup", bOpts));
    sendBtn.dispatchEvent(new MouseEvent("click", bOpts));

    console.log("[DS] ✔ Отправлено:", text);
    return true;
  }

  // ---------- функция 1: создать новый чат ----------
  async function newChat() {
    // ИЗМЕНЕНО: Ждем кнопку "+"
    let plusBtn = null;
    const plusStart = Date.now();
    while (Date.now() - plusStart < 10000) {
      for (const p of document.querySelectorAll("svg path")) {
        const d = p.getAttribute("d") || "";
        if (d.includes("M9.67272 0.522841")) {
          plusBtn = p.closest('[role="button"]') || p.closest("button");
          if (plusBtn) break;
        }
      }
      if (plusBtn) break;
      await sleep(100);
    }

    if (!plusBtn) throw new Error("[DS] Кнопка '+' не найдена");
    clickWithMouse(plusBtn);

    await sleep(1000);

    // Ищем пункт «Новый чат»
    let newChatBtn = null;
    const chatStart = Date.now();
    while (Date.now() - chatStart < 3000) {
      const candidates = document.querySelectorAll('div[tabindex="0"], [role="button"]');
      for (const el of candidates) {
        const spans = el.querySelectorAll("span");
        for (const s of spans) {
          if ((s.textContent || "").trim() === "Новый чат") {
            newChatBtn = el;
            break;
          }
        }
        if (newChatBtn) break;
      }
      if (!newChatBtn) {
        for (const p of document.querySelectorAll("svg path")) {
          const d = p.getAttribute("d") || "";
          if (d.startsWith("M8 0.599609")) {
            newChatBtn = p.closest('[tabindex="0"]') || p.closest('[role="button"]');
            if (newChatBtn) break;
          }
        }
      }
      if (newChatBtn) break;
      await sleep(100);
    }

    if (!newChatBtn) throw new Error("[DS] Пункт 'Новый чат' не найден");

    clickWithMouse(newChatBtn);
    await sleep(2000);

    console.log("[DS] ✔ Новый чат создан");
    return true;
  }

  // ---------- поиск переключателя «Глубокое мышление» ----------
  function findThinkingToggle() {
    for (const el of document.querySelectorAll("[aria-pressed]")) {
      const text = (el.textContent || "").trim();
      if (/Глубокое мышление/i.test(text) || /Deep\s*Think/i.test(text) || /^R1$/i.test(text)) {
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

  // ---------- функция 2: включить режим размышления ----------
  async function enableThinking() {
    const toggle = await waitForElement("[aria-pressed]", 3000).then(() => findThinkingToggle());
    if (!toggle) throw new Error("[DS] Переключатель 'Глубокое мышление' не найден");

    const pressed = toggle.getAttribute("aria-pressed") === "true";
    if (pressed) return true;

    clickWithMouse(toggle);
    await sleep(500);

    if (toggle.getAttribute("aria-pressed") !== "true") {
      throw new Error("[DS] Не удалось включить режим размышления");
    }
    return true;
  }

  // ---------- функция 3: выключить режим размышления ----------
  async function disableThinking() {
    const toggle = await waitForElement("[aria-pressed]", 3000).then(() => findThinkingToggle());
    if (!toggle) throw new Error("[DS] Переключатель 'Глубокое мышление' не найден");

    const pressed = toggle.getAttribute("aria-pressed") === "true";
    if (!pressed) return true;

    clickWithMouse(toggle);
    await sleep(500);

    if (toggle.getAttribute("aria-pressed") === "true") {
      throw new Error("[DS] Не удалось выключить режим размышления");
    }
    return true;
  }

  // ---------- НОВОЕ: поиск переключателя «Умный поиск» ----------
  function findSmartSearchToggle() {
    // 1) основной признак — текст span'а внутри [aria-pressed]
    for (const el of document.querySelectorAll("[aria-pressed]")) {
      const spans = el.querySelectorAll("span");
      for (const s of spans) {
        const t = (s.textContent || "").trim();
        if (/^(Search|Умный поиск)$/i.test(t)) return el;
      }
      const title = el.getAttribute("title") || el.getAttribute("aria-label") || "";
      if (/^(Search|Умный поиск)$/i.test(title)) return el;
    }
    // 2) fallback — по характерному SVG-path ручки лупы
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

  // ---------- НОВОЕ: ожидание переключателя «Умный поиск» ----------
  async function waitForSmartSearchToggle(timeout = 5000) {
    const start = Date.now();
    while (Date.now() - start < timeout) {
      const t = findSmartSearchToggle();
      if (t) return t;
      await sleep(100);
    }
    return null;
  }

  // ---------- НОВОЕ: включить умный поиск ----------
  async function enableSmartSearch() {
    const toggle = await waitForSmartSearchToggle();
    if (!toggle) throw new Error("[DS] Переключатель 'Умный поиск' не найден");

    const pressed = toggle.getAttribute("aria-pressed") === "true";
    if (pressed) return true;

    clickWithMouse(toggle);
    await sleep(500);

    if (toggle.getAttribute("aria-pressed") !== "true") {
      throw new Error("[DS] Не удалось включить умный поиск");
    }
    return true;
  }

  // ---------- НОВОЕ: выключить умный поиск ----------
  async function disableSmartSearch() {
    const toggle = await waitForSmartSearchToggle();
    if (!toggle) throw new Error("[DS] Переключатель 'Умный поиск' не найден");

    const pressed = toggle.getAttribute("aria-pressed") === "true";
    if (!pressed) return true;

    clickWithMouse(toggle);
    await sleep(500);

    if (toggle.getAttribute("aria-pressed") === "true") {
      throw new Error("[DS] Не удалось выключить умный поиск");
    }
    return true;
  }

  // ---------- публичный API ----------
  window.sendToDeepSeek      = sendToDeepSeek;
  window.newChat             = newChat;
  window.enableThinking      = enableThinking;
  window.disableThinking     = disableThinking;
  window.enableSmartSearch   = enableSmartSearch;
  window.disableSmartSearch  = disableSmartSearch;

  // ---------- приём команд из content.js ----------
  window.addEventListener(EVT_EXEC, (evt) => {
    const data = evt.detail;
    try {
      if (!data || typeof data !== "object") {
        emitResult(false, "unknown command shape: " + safeStringify(data));
        return;
      }

      switch (data.type) {
        case "send":
          window.sendToDeepSeek(data.text)
              .then(() => emitResult(true, "sent: " + data.text))
              .catch((e) => emitResult(false, "send failed: " + (e && e.message || e)));
          return;

        case "newChat":
          window.newChat()
              .then(() => emitResult(true, "Новый чат создан"))
              .catch((e) => emitResult(false, "newChat failed: " + (e && e.message || e)));
          return;

        case "enableThinking":
          window.enableThinking()
              .then(() => emitResult(true, "Режим размышления включён"))
              .catch((e) => emitResult(false, "enableThinking failed: " + (e && e.message || e)));
          return;

        case "disableThinking":
          window.disableThinking()
              .then(() => emitResult(true, "Режим размышления выключен"))
              .catch((e) => emitResult(false, "disableThinking failed: " + (e && e.message || e)));
          return;

        case "enableSmartSearch":
          window.enableSmartSearch()
              .then(() => emitResult(true, "Умный поиск включён"))
              .catch((e) => emitResult(false, "enableSmartSearch failed: " + (e && e.message || e)));
          return;

        case "disableSmartSearch":
          window.disableSmartSearch()
              .then(() => emitResult(true, "Умный поиск выключен"))
              .catch((e) => emitResult(false, "disableSmartSearch failed: " + (e && e.message || e)));
          return;

        case "eval": {
          let result;
          try { result = (0, eval)(data.code); }
          catch (e) { emitResult(false, "eval error: " + (e && e.message || e)); return; }

          if (result && typeof result.then === "function") {
            result.then(
                (v) => emitResult(true, "→ " + safeStringify(v)),
                (err) => emitResult(false, "async error: " + (err && err.message || err))
            );
          } else {
            emitResult(true, "→ " + safeStringify(result));
          }
          return;
        }

        case "cmd": {
          let result;
          try { result = (0, eval)(data.cmd); }
          catch (e) { emitResult(false, "eval error: " + (e && e.message || e)); return; }
          emitResult(true, "→ " + safeStringify(result));
          return;
        }

        default:
          emitResult(false, "unknown command type: " + safeStringify(data.type));
      }
    } catch (e) {
      emitResult(false, "handler error: " + (e && e.message || e));
    }
  });

  try { window.dispatchEvent(new CustomEvent(EVT_READY)); } catch (_) {}

  console.info("[DS-INTERCEPT] fetch/XHR patched →", TARGET_PATH);
  console.info("[DS-INTERCEPT] window.{sendToDeepSeek,newChat,enableThinking,disableThinking,enableSmartSearch,disableSmartSearch} attached");
})();