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

  // --- НОВОЕ: константы для связи с Java-сервером ---
  const SERVER_URL = "http://localhost:11434";
  const urlParams = new URLSearchParams(window.location.search);
  const tabId = urlParams.get('tabId') || 'tab-' + Math.random().toString(36).substring(2, 10);

  let __streamSeq = 0;
  const nextStreamId = () =>
      "s" + Date.now() + "-" + (++__streamSeq) + "-" +
      Math.random().toString(36).slice(2, 8);

  // --- НОВОЕ: отправка данных на Java-сервер (логи, стримы) ---
  const postLog = (level, text, streamId = "", streamType = "") => {
    try {
      fetch(SERVER_URL + "/log", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ level, text, streamId, streamType, tabId })
      }).catch(() => {});
    } catch (_) {}
  };

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
          // НОВОЕ: дублируем консоль на Java-сервер
          if (level === "error") postLog("console-error", text);
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
      if (!reader) { emit("", "done", streamId); postLog("stream-done", "", streamId); return; }
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
                postLog("stream", line, streamId, "RESPONSE"); // НОВОЕ: отправка стрима в Java
              }
            }
          }
        }
        if (buffer.trim()) {
          for (const line of buffer.split("\n")) {
            if (line.startsWith("data:")) {
              emit(line, "sse", streamId);
              postLog("stream", line, streamId, "RESPONSE");
            }
          }
        }
      } catch (err) {
        console.warn("[DS-INTERCEPT] stream error:", err);
      } finally {
        emit("", "done", streamId);
        postLog("stream-done", "", streamId); // НОВОЕ: конец стрима в Java
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
          postLog("stream", line, streamId, "RESPONSE"); // НОВОЕ: отправка XHR-стрима в Java
        }
      }
    });

    xhr.addEventListener("loadend", () => {
      if (target && streamId) {
        emit("", "done", streamId);
        postLog("stream-done", "", streamId); // НОВОЕ
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

  function base64ToBlob(b64, mime) {
    const bin = atob(b64 || "");
    const len = bin.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
    return new Blob([bytes], { type: mime || "application/octet-stream" });
  }

  // ---------- НОВОЕ: определение текстовых файлов и декодирование ----------
  const TEXT_EXT_RE = /\.(txt|text|md|markdown|json|jsonl|csv|tsv|html?|xml|ya?ml|log|ini|conf|cfg|toml|properties|js|jsx|ts|tsx|py|java|kt|c|cc|cpp|cxx|h|hpp|cs|go|rs|rb|php|pl|lua|swift|m|mm|scala|clj|ex|exs|erl|hs|r|sql|sh|bash|zsh|bat|cmd|ps1|tex|rtf|env)$/i;
  const TEXT_MIME_RE = /^(text\/|application\/(json|xml|x-yaml|yaml|javascript|typescript|sql|x-sh|x-shellscript|x-httpd-php))/i;

  function isTextFile(f) {
    const name = (f && f.name) || "";
    const mime = (f && f.mimeType) || "";
    if (TEXT_EXT_RE.test(name)) return true;
    if (TEXT_MIME_RE.test(mime)) return true;
    return false;
  }

  function decodeBase64ToText(b64) {
    const bin = atob(b64 || "");
    const len = bin.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder("utf-8", { fatal: false }).decode(bytes);
  }

  // ---------- загрузка бинарных файлов ----------
  async function uploadFilesToInput(files) {
    if (!files || files.length === 0) return true;

    console.log("[Upload] attempt: files =", files.map(f => f.name).join(", "));

    let input = document.querySelector('input[type="file"]');
    if (!input) {
      input = await waitForElement('input[type="file"]', 5000);
    }
    if (!input) {
      const candidates = document.querySelectorAll('[role="button"], button');
      for (const el of candidates) {
        const label = (
            (el.getAttribute("aria-label") || "") + " " +
            (el.getAttribute("title") || "") + " " +
            (el.textContent || "")
        ).toLowerCase();
        if (/attach|файл|upload|прикреп|file/i.test(label)) {
          try { clickWithMouse(el); } catch (_) {}
          await sleep(400);
          input = document.querySelector('input[type="file"]');
          if (input) break;
        }
      }
    }
    if (!input) {
      throw new Error("[Upload] input[type=file] не найден на странице");
    }

    console.log("[Upload] found input, multiple =", input.multiple);

    if (files.length > 1 && !input.multiple) {
      try { input.multiple = true; } catch (_) {}
    }

    const dt = new DataTransfer();
    for (const f of files) {
      const blob = base64ToBlob(f.dataBase64, f.mimeType);
      const fileName = (f.name || ("file-" + Date.now())).replace(/[\r\n]/g, "_");
      const file = new File([blob], fileName, {
        type: f.mimeType || "application/octet-stream",
        lastModified: Date.now()
      });
      dt.items.add(file);
      console.log("[Upload] prepared:", fileName, "size:", blob.size, "type:", file.type);
    }

    let assigned = false;
    try {
      input.files = dt.files;
      assigned = !!(input.files && input.files.length === files.length);
      console.log("[Upload] native setter: assigned =", assigned,
          " count =", input.files ? input.files.length : "?");
    } catch (e) {
      console.warn("[Upload] native setter failed:", e);
    }

    if (!assigned) {
      try {
        Object.defineProperty(input, "files", {
          configurable: true,
          get: () => dt.files
        });
        console.log("[Upload] defineProperty fallback used");
        assigned = true;
      } catch (e) {
        throw new Error("[Upload] cannot assign files to input: " + e.message);
      }
    }

    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
    console.log("[Upload] change events dispatched");

    const ok = await waitForUploadSettle(files);
    console.log("[Upload] settle result:", ok);
    return ok;
  }

  async function waitForUploadSettle(files, timeoutMs = 60000) {
    const fileCount = files.length;
    const start = Date.now();
    let sawProgress = false;
    let lastProgressAt = 0;

    await sleep(500);

    while (Date.now() - start < timeoutMs) {
      const progress = document.querySelectorAll(
          '[class*="uploading"], [class*="upload-progress"], [class*="uploadProgress"], ' +
          '[role="progressbar"], [class*="file-uploading"], [class*="uploading-file"]'
      );
      if (progress.length > 0) {
        if (!sawProgress) console.log("[Upload] progress indicator detected");
        sawProgress = true;
        lastProgressAt = Date.now();
        await sleep(250);
        continue;
      }

      if (sawProgress && Date.now() - lastProgressAt > 800) {
        console.log("[Upload] progress disappeared → assume complete");
        await sleep(400);
        return true;
      }

      const attachments = document.querySelectorAll(
          '[class*="file-chip"], [class*="fileCard"], [class*="file-card"], ' +
          '[class*="file-preview"], [class*="filePreview"], ' +
          '[class*="file-item"], [class*="fileItem"], ' +
          '[class*="attachment"], [class*="attached-file"]'
      );
      if (attachments.length >= fileCount) {
        console.log("[Upload] attachment chips found:", attachments.length);
        await sleep(400);
        return true;
      }

      if (!sawProgress && Date.now() - start > 12000) {
        console.warn("[Upload] no activity after 12s → assuming failed");
        return false;
      }

      await sleep(300);
    }
    console.warn("[Upload] timeout");
    return sawProgress;
  }

  // ---------- Continue watcher ----------
  const CONTINUE_TEXT = /^Continue$/i;
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

  // ---------- sendToDeepSeek ----------
  async function sendToDeepSeek(text, files) {
    let finalText = (text || "").trim();
    const binaryFiles = [];
    const inlinedNames = [];

    if (files && Array.isArray(files) && files.length > 0) {
      for (const f of files) {
        if (isTextFile(f)) {
          try {
            const content = decodeBase64ToText(f.dataBase64 || "");
            const fname = (f.name || "file.txt").replace(/[\r\n]/g, "_");
            if (finalText.length > 0) finalText += "\n\n";
            finalText +=
                "=== Attached file: " + fname + " ===\n" +
                content +
                (content.endsWith("\n") ? "" : "\n") +
                "=== End of file: " + fname + " ===";
            inlinedNames.push(fname);
            console.log("[Inline] text file inlined:", fname, "(", content.length, "chars )");
          } catch (e) {
            console.error("[Inline] failed for", f.name, "— fallback to upload:", e);
            binaryFiles.push(f);
          }
        } else {
          binaryFiles.push(f);
        }
      }
    }

    if (binaryFiles.length > 0) {
      try {
        await uploadFilesToInput(binaryFiles);
      } catch (e) {
        console.error("[Upload] binary files failed:", e);
      }
    }

    if (!finalText && binaryFiles.length > 0) {
      finalText = "Please analyze the attached file(s).";
    }
    if (!finalText) {
      throw new Error("[DS] no content to send (empty text and no files)");
    }

    const input = await waitForElement('[placeholder="Сообщение для DeepSeek"]', 10000);
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

      const ok = document.execCommand("insertText", false, finalText);
      if (!ok) {
        input.textContent = finalText;
        input.dispatchEvent(new InputEvent("input", {
          bubbles: true, data: finalText, inputType: "insertText"
        }));
      }
    } else if (input.tagName === "TEXTAREA" || input.tagName === "INPUT") {
      const proto = input.tagName === "TEXTAREA"
          ? HTMLTextAreaElement.prototype
          : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, "value").set.call(input, finalText);
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    } else {
      throw new Error("[DS] Неизвестный тип поля ввода");
    }

    await sleep(150);

    let sendBtn = null;
    const btnStartTime = Date.now();
    while (Date.now() - btnStartTime < 20000) {
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

    console.log("[DS] ✔ Отправлено:",
        (inlinedNames.length ? "[" + inlinedNames.join(", ") + "] " : "")
        + (binaryFiles.length ? "[" + binaryFiles.length + " binary] " : "")
        + "(text length: " + finalText.length + ")");
    return true;
  }

  // ---------- новый чат ----------
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

  // ---------- переключатель «Глубокое мышление» ----------
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

  // ---------- переключатель «Умный поиск» ----------
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
    const pressed = toggle.getAttribute("aria-pressed") === "true";
    if (pressed) return true;
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

  setupContinueWatcher();

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
          window.sendToDeepSeek(data.text, data.files)
              .then(() => emitResult(true, "sent: " + (data.text || "")))
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

  // ==========================================================
  // НОВОЕ: Подключение к Java-серверу (SSE) для очистки истории
  // ==========================================================
  function connectSSE() {
    console.log("[DS-INTERCEPT] Connecting to Java Bridge SSE for tabId:", tabId);
    const evtSource = new EventSource(SERVER_URL + "/events?tabId=" + tabId);

    evtSource.onopen = () => {
      // Регистрируем вкладку на сервере
      postLog("hello", JSON.stringify({ url: location.href, ua: navigator.userAgent }));
    };

    evtSource.onmessage = function(event) {
      try {
        const data = JSON.parse(event.data);
        console.log("[DS-INTERCEPT] Command received from Java:", data);

        // Обработка команд, приходящих напрямую от Java-сервера
        if (data.type === 'newChat') {
          window.newChat()
              .then(() => postLog("result", "Новый чат создан"))
              .catch(e => postLog("error", "newChat failed: " + e.message));
        } else if (data.type === 'send') {
          window.sendToDeepSeek(data.text, data.files)
              .then(() => postLog("result", "sent: " + (data.text || "")))
              .catch(e => postLog("error", "send failed: " + e.message));
        } else if (data.type === 'enableThinking') {
          window.enableThinking().catch(e => postLog("error", e.message));
        } else if (data.type === 'disableThinking') {
          window.disableThinking().catch(e => postLog("error", e.message));
        } else if (data.type === 'enableSmartSearch') {
          window.enableSmartSearch().catch(e => postLog("error", e.message));
        } else if (data.type === 'disableSmartSearch') {
          window.disableSmartSearch().catch(e => postLog("error", e.message));
        } else if (data.type === 'eval') {
          try {
            const res = (0, eval)(data.code);
            postLog("result", safeStringify(res));
          } catch(e) {
            postLog("error", "eval error: " + e.message);
          }
        }
      } catch (e) {
        console.error("[DS-INTERCEPT] SSE parse error:", e);
      }
    };

    evtSource.onerror = function() {
      console.warn("[DS-INTERCEPT] SSE disconnected, retrying in 3s...");
      evtSource.close();
      setTimeout(connectSSE, 3000);
    };
  }

  // Отправка сообщения о закрытии вкладки на сервер
  window.addEventListener('beforeunload', () => {
    try {
      navigator.sendBeacon(SERVER_URL + "/log", JSON.stringify({ level: "bye", tabId, text: "window closed" }));
    } catch (_) {}
  });

  // Запуск SSE
  connectSSE();

  try { window.dispatchEvent(new CustomEvent(EVT_READY)); } catch (_) {}

  console.info("[DS-INTERCEPT] fetch/XHR patched →", TARGET_PATH);
  console.info("[DS-INTERCEPT] window.{sendToDeepSeek,newChat,enableThinking,disableThinking,enableSmartSearch,disableSmartSearch} attached");
})();