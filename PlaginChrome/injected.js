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

  // ---------- стоп/продолжение ----------
  const STOP_PATH_MARKER = "M2 4.88C2 3.68009";
  const CONTINUE_TEXT = /^(Continue|Продолжить|Продолжить генерацию)$/i;

  let stopRequested = false;

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

  const SKIP_PREFIXES = ["[DS-INTERCEPT]", "[DS] ", "[Captcha] ", "[DS-MARKER]"];

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
              if (line.startsWith("data:")) {
                emit(line, "sse", streamId);
              }
            }
          }
        }
        if (buffer.trim()) {
          for (const line of buffer.split("\n")) {
            if (line.startsWith("data:")) {
              emit(line, "sse", streamId);
            }
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
        if (line.startsWith("data:")) {
          emit(line, "sse", streamId);
        }
      }
    });

    xhr.addEventListener("loadend", () => {
      if (target && streamId) {
        emit("", "done", streamId);
      }
    });

    return xhr;
  }
  PatchedXHR.prototype = OrigXHR.prototype;
  window.XMLHttpRequest = PatchedXHR;

  // ---------- helpers ----------
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

  async function waitForElement(selector, timeout = 10000) {
    const start = Date.now();
    while (Date.now() - start < timeout) {
      const el = document.querySelector(selector);
      if (el) return el;
      await sleep(100);
    }
    return null;
  }

  // ============================================================
  //  Устойчивый поиск поля ввода чата (RU / EN / любой textarea)
  // ============================================================
  const KNOWN_PLACEHOLDERS = [
    "Message DeepSeek",
    "Сообщение для DeepSeek",
    "Send a message",
    "Написать сообщение",
    "Type a message",
    "Введите сообщение"
  ];

  function isVisible(el) {
    if (!el) return false;
    if (el.offsetParent === null && el.getClientRects().length === 0) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function findChatInputSync() {
    for (const ph of KNOWN_PLACEHOLDERS) {
      const el = document.querySelector(
          'textarea[placeholder="' + ph.replace(/"/g, '\\"') + '"]'
      );
      if (isVisible(el)) return el;
    }
    const byPlaceholder = document.querySelectorAll('textarea[placeholder]');
    for (const el of byPlaceholder) {
      if (isVisible(el)) return el;
    }
    const textareas = document.querySelectorAll('textarea');
    for (const el of textareas) {
      if (isVisible(el)) return el;
    }
    const editables = document.querySelectorAll('[contenteditable="true"]');
    for (const el of editables) {
      if (isVisible(el)) return el;
    }
    return null;
  }

  async function findChatInput(timeout = 10000) {
    const start = Date.now();
    while (Date.now() - start < timeout) {
      const el = findChatInputSync();
      if (el) return el;
      await sleep(100);
    }
    return null;
  }

  // ============================================================
  //  МАРКЕРЫ ФАЙЛОВ
  // ============================================================
  const FILE_MARKER_RE =
      /<!--DSFILE:BEGIN([^>]*)-->([\s\S]*?)<!--DSFILE:END-->/g;

  function parseMarkerAttrs(raw) {
    const attrs = {};
    const re = /(\w+)=(?:"([^"]*)"|(\S+))/g;
    let m;
    while ((m = re.exec(raw)) !== null) {
      const key = m[1];
      const val = m[2] !== undefined ? m[2] : m[3];
      attrs[key] = val;
    }
    return attrs;
  }

  /**
   * Извлекает из текста маркеры файлов.
   * Возвращает { cleanText, files: [{name,mime,size,path,dataBase64}] }.
   */
  function extractFileMarkers(text) {
    const files = [];
    const clean = String(text || "").replace(FILE_MARKER_RE, (full, rawAttrs, payload) => {
      const attrs = parseMarkerAttrs(rawAttrs || "");
      const name = attrs.name ? decodeURIComponent(attrs.name) : "file.bin";
      const mime = attrs.mime || "application/octet-stream";
      const size = Number(attrs.size || 0) || 0;
      const path = attrs.path ? decodeURIComponent(attrs.path) : name;
      const dataBase64 = String(payload || "").replace(/\s+/g, "");
      if (!dataBase64) return "";
      files.push({ name, mime, size, path, dataBase64 });
      return "";
    }).replace(/\n{3,}/g, "\n\n").trim();

    return { cleanText: clean, files };
  }

  function base64ToBytes(b64) {
    const bin = atob(b64 || "");
    const len = bin.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  function base64ToFile(f) {
    const bytes = base64ToBytes(f.dataBase64);
    return new File([bytes], f.name, {
      type: f.mime || "application/octet-stream",
      lastModified: Date.now()
    });
  }

  // ============================================================
  //  Вставка файлов в DeepSeek через input[type=file] + DataTransfer
  // ============================================================
  const ATTACHMENT_CHIP_SELECTORS = [
    '[class*="file-chip"]',
    '[class*="fileCard"]',
    '[class*="file-card"]',
    '[class*="file-preview"]',
    '[class*="filePreview"]',
    '[class*="file-item"]',
    '[class*="fileItem"]',
    '[class*="attachment"]',
    '[class*="attached-file"]',
  ].join(",");

  async function findFileInput(timeout = 5000) {
    let input = document.querySelector('input[type="file"]');
    if (input) return input;

    // fallback: нажать на кнопку «прикрепить» и подождать input
    const btns = document.querySelectorAll('[role="button"], button');
    for (const el of btns) {
      const label = (
          (el.getAttribute("aria-label") || "") + " " +
          (el.getAttribute("title") || "") + " " +
          (el.textContent || "")
      ).toLowerCase();
      if (/attach|файл|upload|прикреп|file/i.test(label)) {
        try { clickWithMouse(el); } catch (_) {}
        await sleep(300);
        input = document.querySelector('input[type="file"]');
        if (input) return input;
      }
    }
    return await waitForElement('input[type="file"]', timeout);
  }

  function countAttachmentChips() {
    return document.querySelectorAll(ATTACHMENT_CHIP_SELECTORS).length;
  }

  async function waitForAttachments(expected, timeoutMs = 30000) {
    const start = Date.now();
    let sawProgress = false;
    let lastProgressAt = 0;
    while (Date.now() - start < timeoutMs) {
      const progress = document.querySelectorAll(
          '[class*="uploading"], [class*="upload-progress"], ' +
          '[class*="uploadProgress"], [role="progressbar"]'
      );
      if (progress.length > 0) {
        sawProgress = true;
        lastProgressAt = Date.now();
        await sleep(250);
        continue;
      }
      if (sawProgress && Date.now() - lastProgressAt > 800) {
        // прогресс исчез → считаем завершённым
        const chips = countAttachmentChips();
        if (chips >= expected) return true;
      }
      const chips = countAttachmentChips();
      if (chips >= expected) {
        console.log("[DS-MARKER] attachments ready:", chips, "/", expected);
        return true;
      }
      await sleep(300);
    }
    console.warn("[DS-MARKER] waitForAttachments TIMEOUT (", countAttachmentChips(), "/", expected, ")");
    return false;
  }

  /**
   * Вставляет массив файлов в input[type=file] DeepSeek.
   * @returns {Promise<boolean>} true если чипы файлов появились
   */
  async function attachFilesToInput(files) {
    if (!files || files.length === 0) return true;

    const input = await findFileInput(8000);
    if (!input) {
      console.error("[DS-MARKER] input[type=file] not found");
      return false;
    }

    if (files.length > 1 && !input.multiple) {
      try { input.multiple = true; } catch (_) {}
    }

    const dt = new DataTransfer();
    for (const f of files) {
      dt.items.add(base64ToFile(f));
    }

    let assigned = false;
    try {
      input.files = dt.files;
      assigned = !!(input.files && input.files.length === files.length);
    } catch (e) {
      console.warn("[DS-MARKER] native setter failed:", e);
    }

    if (!assigned) {
      try {
        Object.defineProperty(input, "files", {
          configurable: true,
          get: () => dt.files
        });
        assigned = true;
        console.log("[DS-MARKER] defineProperty fallback used");
      } catch (e) {
        console.error("[DS-MARKER] cannot assign files:", e);
        return false;
      }
    }

    const before = countAttachmentChips();
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
    console.log("[DS-MARKER] change dispatched; chips before:", before);

    const ok = await waitForAttachments(files.length, 30000);
    if (!ok) {
      // fallback через clipboard (имя + содержимое)
      await attachViaClipboard(files).catch(() => {});
    }
    return ok;
  }

  /**
   * Fallback: копирует каждый файл (имя + \n + содержимое) в буфер
   * и пытается вставить в поле ввода.
   * Полезно, если input[type=file] не сработал.
   */
  async function attachViaClipboard(files) {
    const input = findChatInputSync();
    if (!input) return false;
    input.focus();

    for (const f of files) {
      let textContent = "";
      try {
        const bytes = base64ToBytes(f.dataBase64);
        textContent = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
      } catch (_) {
        continue;
      }
      const clip = f.name + "\n" + textContent;

      let wrote = false;
      try {
        await navigator.clipboard.writeText(clip);
        wrote = true;
      } catch (e) {
        console.warn("[DS-MARKER] clipboard write failed:", e);
      }

      if (!wrote) break;

      // Пытаемся вставить содержимое буфера
      try {
        document.execCommand("paste");
      } catch (_) {
        // на всякий случай — вставим программно
        insertTextIntoInput(input, clip);
      }
      await sleep(300);
    }
    return true;
  }

  function insertTextIntoInput(input, text) {
    if (!input) return;
    input.focus();

    if (input.isContentEditable) {
      const range = document.createRange();
      range.selectNodeContents(input);
      range.collapse(false);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);

      const ok = document.execCommand("insertText", false, text);
      if (!ok) {
        input.textContent = (input.textContent || "") + text;
        input.dispatchEvent(new InputEvent("input", {
          bubbles: true, data: text, inputType: "insertText"
        }));
      }
    } else if (input.tagName === "TEXTAREA" || input.tagName === "INPUT") {
      const proto = input.tagName === "TEXTAREA"
          ? HTMLTextAreaElement.prototype
          : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
      setter.call(input, text);
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    }
  }

  // ============================================================
  //  Continue watcher
  // ============================================================
  let continueClickTimer = null;
  let lastContinueClickAt = 0;
  let lastContinueEl = null;

  function findContinueButton() {
    const spans = document.querySelectorAll("span.ds-button__content");
    for (const span of spans) {
      const text = (span.textContent || "").trim();
      if (!CONTINUE_TEXT.test(text)) continue;
      const btn = span.closest('[role="button"]') || span.closest("button") ||
          span.closest(".ds-button") || span.parentElement;
      if (!btn) continue;
      if (btn.disabled) continue;
      if (btn.getAttribute("aria-disabled") === "true") continue;
      const rect = btn.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) continue;
      return btn;
    }
    return null;
  }

  function clickContinueIfPresent() {
    if (stopRequested) return false;
    const btn = findContinueButton();
    if (!btn) return false;
    const now = Date.now();
    if (btn === lastContinueEl && now - lastContinueClickAt < 700) return false;
    lastContinueEl = btn;
    lastContinueClickAt = now;
    try { clickWithMouse(btn); } catch (_) {}
    return true;
  }

  function scheduleContinueCheck() {
    if (continueClickTimer) return;
    continueClickTimer = setTimeout(() => {
      continueClickTimer = null;
      clickContinueIfPresent();
    }, 50);
  }

  function setupContinueWatcher() {
    scheduleContinueCheck();
    const root = document.documentElement || document;
    if (!root) return;
    try {
      const observer = new MutationObserver(() => scheduleContinueCheck());
      observer.observe(root, { childList: true, subtree: true, characterData: true });
    } catch (_) {}
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", scheduleContinueCheck, { once: true });
    }
    setInterval(scheduleContinueCheck, 2000);
  }

  // ============================================================
  //  СТОП / ПРОДОЛЖИТЬ
  // ============================================================
  function findStopButton() {
    for (const p of document.querySelectorAll("svg path")) {
      const d = p.getAttribute("d") || "";
      if (d.includes(STOP_PATH_MARKER)) {
        return p.closest('[role="button"]') || p.closest("button") || p.parentElement;
      }
    }
    return null;
  }

  async function stopGeneration() {
    const btn = findStopButton();
    if (!btn) throw new Error("[DS] Кнопка 'Стоп' не найдена");
    stopRequested = true;
    clickWithMouse(btn);
    console.log("[DS] ✔ Генерация остановлена");
    return true;
  }

  async function resumeGeneration() {
    stopRequested = false;
    const btn = findContinueButton();
    if (!btn) {
      const start = Date.now();
      while (Date.now() - start < 3000) {
        await sleep(100);
        const b = findContinueButton();
        if (b) {
          lastContinueEl = b;
          lastContinueClickAt = Date.now();
          clickWithMouse(b);
          return true;
        }
      }
      throw new Error("[DS] Кнопка 'Продолжить' не найдена");
    }
    lastContinueEl = btn;
    lastContinueClickAt = Date.now();
    clickWithMouse(btn);
    return true;
  }

  // ============================================================
  //  sendToDeepSeek
  // ============================================================
  async function sendToDeepSeek(text, files) {
    stopRequested = false;

    // 1. Парсим маркеры из текста
    const { cleanText, files: markerFiles } = extractFileMarkers(text || "");
    const allFiles = [];
    if (Array.isArray(files)) allFiles.push(...files);
    if (markerFiles.length) allFiles.push(...markerFiles);

    console.log(
        "[DS-MARKER] send: cleanText=" + cleanText.length +
        " chars, files=" + allFiles.length +
        " (" + allFiles.map(f => f.name).join(", ") + ")"
    );

    // 2. Прикрепляем файлы (DataTransfer → input[type=file])
    if (allFiles.length > 0) {
      await attachFilesToInput(allFiles);
    }

    // 3. Вставляем чистый текст
    if (!cleanText) {
      // Если файлов нет и текста нет — нечего отправлять
      if (allFiles.length === 0) {
        throw new Error("[DS] no content to send");
      }
    }

    const input = await findChatInput(10000);
    if (!input) throw new Error("[DS] Поле ввода не найдено");

    if (cleanText) {
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
      insertTextIntoInput(input, cleanText);
      await sleep(150);
    }

    // 4. Отправка
    let sendBtn = null;
    const btnStart = Date.now();
    while (Date.now() - btnStart < 20000) {
      if (!sendBtn) {
        const icons = document.querySelectorAll(".ds-button__icon.ds-button__icon--last-child");
        for (const icon of icons) {
          const path = icon.querySelector("svg path");
          if (path && /M8\.3125\s+0\.980206/.test(path.getAttribute("d") || "")) {
            sendBtn = icon.closest(".ds-button") || icon.closest("button") ||
                icon.parentElement || icon;
            break;
          }
        }
      }
      if (sendBtn) {
        const disabled = sendBtn.disabled
            || sendBtn.getAttribute("aria-disabled") === "true";
        if (!disabled) break;
      }
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

    console.log("[DS] ✔ Отправлено (cleanText:", cleanText.length, "chars, files:", allFiles.length, ")");
    return true;
  }

  // ============================================================
  //  newChat / thinking / smart-search
  // ============================================================
  async function newChat() {
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

    let newChatBtn = null;
    const chatStart = Date.now();
    while (Date.now() - chatStart < 3000) {
      const candidates = document.querySelectorAll('div[tabindex="0"], [role="button"]');
      for (const el of candidates) {
        const spans = el.querySelectorAll("span");
        for (const s of spans) {
          const t = (s.textContent || "").trim();
          if (t === "Новый чат" || t === "New chat" || /^New chat$/i.test(t)) {
            newChatBtn = el; break;
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
    return true;
  }

  function findThinkingToggle() {
    for (const el of document.querySelectorAll("[aria-pressed]")) {
      const text = (el.textContent || "").trim();
      if (/Глубокое мышление/i.test(text) || /Deep\s*Think/i.test(text) || /^R1$/i.test(text)) return el;
      const title = el.getAttribute("title") || el.getAttribute("aria-label") || "";
      if (/Глубокое мышление/i.test(title) || /Deep\s*Think/i.test(title)) return el;
    }
    for (const el of document.querySelectorAll("[aria-pressed]")) {
      for (const p of el.querySelectorAll("svg path")) {
        const d = p.getAttribute("d") || "";
        if (d.startsWith("M8.00192 6.64454")) return el;
      }
    }
    return null;
  }

  async function enableThinking() {
    const toggle = await waitForElement("[aria-pressed]", 3000).then(() => findThinkingToggle());
    if (!toggle) throw new Error("[DS] Переключатель 'Глубокое мышление' не найден");
    if (toggle.getAttribute("aria-pressed") === "true") return true;
    clickWithMouse(toggle);
    await sleep(500);
    if (toggle.getAttribute("aria-pressed") !== "true") {
      throw new Error("[DS] Не удалось включить режим размышления");
    }
    return true;
  }

  async function disableThinking() {
    const toggle = await waitForElement("[aria-pressed]", 3000).then(() => findThinkingToggle());
    if (!toggle) throw new Error("[DS] Переключатель 'Глубокое мышление' не найден");
    if (toggle.getAttribute("aria-pressed") !== "true") return true;
    clickWithMouse(toggle);
    await sleep(500);
    if (toggle.getAttribute("aria-pressed") === "true") {
      throw new Error("[DS] Не удалось выключить режим размышления");
    }
    return true;
  }

  function findSmartSearchToggle() {
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
        if (d.includes("M1.6399999856948853,8 C1.6399999856948853,8 14.359999656677246,8 14.359999656677246,8")) hasSearchHandle = true;
        if (d.startsWith("M8.00192 6.64454")) isThinking = true;
      }
      if (hasSearchHandle && !isThinking) return el;
    }
    return null;
  }

  async function waitForSmartSearchToggle(timeout = 5000) {
    const start = Date.now();
    while (Date.now() - start < timeout) {
      const t = findSmartSearchToggle();
      if (t) return t;
      await sleep(100);
    }
    return null;
  }

  async function enableSmartSearch() {
    const toggle = await waitForSmartSearchToggle();
    if (!toggle) throw new Error("[DS] Переключатель 'Умный поиск' не найден");
    if (toggle.getAttribute("aria-pressed") === "true") return true;
    clickWithMouse(toggle);
    await sleep(500);
    if (toggle.getAttribute("aria-pressed") !== "true") {
      throw new Error("[DS] Не удалось включить умный поиск");
    }
    return true;
  }

  async function disableSmartSearch() {
    const toggle = await waitForSmartSearchToggle();
    if (!toggle) throw new Error("[DS] Переключатель 'Умный поиск' не найден");
    if (toggle.getAttribute("aria-pressed") !== "true") return true;
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
  window.stopGeneration      = stopGeneration;
  window.resumeGeneration    = resumeGeneration;

  setupContinueWatcher();

  // ---------- приём команд ----------
  window.addEventListener(EVT_EXEC, (evt) => {
    const data = evt.detail;
    try {
      if (!data || typeof data !== "object") {
        emitResult(false, "unknown command shape: " + safeStringify(data));
        return;
      }

      switch (data.type) {
        case "send":
          window.sendToDeepSeek(data.text, data.files)
              .then(() => emitResult(true, "sent"))
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
        case "stopGeneration":
          window.stopGeneration()
              .then(() => emitResult(true, "Генерация остановлена"))
              .catch((e) => emitResult(false, "stopGeneration failed: " + (e && e.message || e)));
          return;
        case "resumeGeneration":
          window.resumeGeneration()
              .then(() => emitResult(true, "Генерация продолжена"))
              .catch((e) => emitResult(false, "resumeGeneration failed: " + (e && e.message || e)));
          return;
        case "getState":
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
  console.info("[DS-INTERCEPT] window.{sendToDeepSeek,newChat,enableThinking,disableThinking,enableSmartSearch,disableSmartSearch,stopGeneration,resumeGeneration} attached");
  console.info("[DS-INTERCEPT] marker support: <!--DSFILE:BEGIN ... -->base64<!--DSFILE:END-->");
})();