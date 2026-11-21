(function () {
  "use strict";

  class BridgeApi {
    constructor(baseUrl) {
      this.baseUrl = (baseUrl || "").replace(/\/+$/, "");
    }

    async _json(method, path, body, timeoutMs = 130000) {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(new Error("request timeout")), timeoutMs);
      try {
        const resp = await fetch(this.baseUrl + path, {
          method,
          headers: body ? { "Content-Type": "application/json" } : undefined,
          body: body ? JSON.stringify(body) : undefined,
          signal: ctrl.signal,
        });
        let data;
        try { data = await resp.json(); }
        catch (_) { throw new Error("invalid JSON from server (HTTP " + resp.status + ")"); }

        if (!resp.ok && !data.ok) throw new Error(data.error || ("HTTP " + resp.status));
        return data;
      } finally {
        clearTimeout(timer);
      }
    }

    async cmd(type, extra = {}) {
      const data = await this._json("POST", "/api/command", { type, ...extra });
      if (!data.ok) throw new Error(data.error || "unknown error");
      return data.result;
    }

    async ping() {
      const resp = await fetch(this.baseUrl + "/", { method: "GET" });
      if (!resp.ok) throw new Error("server ping failed: HTTP " + resp.status);
      return true;
    }

    // ===== Основные команды =====
    async tabs() { const r = await this.cmd("tabs"); return Array.isArray(r.tabs) ? r.tabs : []; }
    async selectTab(tabId) { return this.cmd("tab", { tabId }); }
    async state() { return this.cmd("state"); }
    async setThinking(on) { return this.cmd(on ? "enableThinking" : "disableThinking"); }
    async setSearch(on) { return this.cmd(on ? "enableSmartSearch" : "disableSmartSearch"); }
    async newChat()   { return this.cmd("newChat"); }
    async stop()      { return this.cmd("stopGeneration"); }
    async resume()    { return this.cmd("resumeGeneration"); }
    async browsers() { const r = await this.cmd("browsers"); return Array.isArray(r.browsers) ? r.browsers : []; }
    async openBrowser(index) { return this.cmd("openBrowser", { index }); }
    async cmdFile(path, text = "") { return this.cmd("file", { path, text }); }

    // ===== Пошаговые команды =====
    async attach(files)      { return this.cmd("attach", { files }); }
    async detach()           { return this.cmd("detach"); }
    async setText(text)      { return this.cmd("setText", { text }); }
    async appendText(text)   { return this.cmd("appendText", { text }); }
    async clickSend()        { return this.cmd("clickSend"); }
    async sendWithAttached(text) { return this.cmd("sendWithAttached", { text }); }

    // ===== Буфер обмена =====
    async clipboardAttach(files) { return this.cmd("clipboardAttach", { files }); }
    async clipboardCopy(files)   { return this.cmd("clipboardCopy", { files }); }
    async clipboardPaste()       { return this.cmd("clipboardPaste"); }

    // ----------------------------------------------------------
    //  Потоковый чат (NDJSON)
    //  opts: { signal, clipboard, skipAttach, strict, setTextFirst,
    //          files: [{ name, mimeType, dataBase64 }, ...] }
    // ----------------------------------------------------------
    chatStream(messages, opts = {}) {
      const url = this.baseUrl + "/api/chat";
      const body = { model: "DeepSeekCloud", messages, stream: true };

      if (opts.clipboard    === true) body.clipboard = true;
      if (opts.skipAttach   === true) body.skipAttach = true;
      if (opts.strict       === true) body.strict = true;
      if (opts.setTextFirst === false) body.setTextFirst = false;
      // ← НОВОЕ: передаём файлы, которые нужно прикрепить перед отправкой
      if (Array.isArray(opts.files) && opts.files.length > 0) body.files = opts.files;

      const listeners = { chunk: [], done: [], error: [] };
      let aborted = false;
      let finished = false;

      const ctrl = new AbortController();
      if (opts.signal) {
        opts.signal.addEventListener("abort", () => { aborted = true; ctrl.abort(); }, { once: true });
      }

      const emitError = (err) => {
        if (finished) return;
        finished = true;
        if (!aborted) listeners.error.forEach(fn => { try { fn(err); } catch (_) {} });
      };
      const emitDone = () => {
        if (finished) return;
        finished = true;
        listeners.done.forEach(fn => { try { fn(); } catch (_) {} });
      };

      const promise = (async () => {
        let resp;
        try {
          resp = await fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
            signal: ctrl.signal,
          });
        } catch (e) {
          emitError(e);
          return;
        }

        if (!resp.ok) {
          let errText = "HTTP " + resp.status;
          try { const j = await resp.json(); if (j.error) errText = j.error; } catch (_) {}
          emitError(new Error(errText));
          return;
        }

        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buf = "";

        try {
          while (true) {
            const { value, done } = await reader.read();
            if (done) break;

            buf += decoder.decode(value, { stream: true });
            let idx;

            // Парсим NDJSON (каждая строка — отдельный JSON)
            while ((idx = buf.indexOf("\n")) !== -1) {
              const line = buf.slice(0, idx).trim();
              buf = buf.slice(idx + 1);
              if (!line) continue;

              let chunk;
              try { chunk = JSON.parse(line); } catch (_) { continue; }

              // Если сервер вернул ошибку (например, из-за strict режима)
              if (chunk.error) {
                emitError(new Error(chunk.error));
                return;
              }

              // Обработка контента
              if (chunk.message) {
                if (typeof chunk.message.content === "string" && chunk.message.content) {
                  listeners.chunk.forEach(fn => {
                    try { fn(chunk.message.content, chunk.type || "RESPONSE"); } catch (_) {}
                  });
                }
                if (typeof chunk.message.reasoning_content === "string" && chunk.message.reasoning_content) {
                  listeners.chunk.forEach(fn => {
                    try { fn(chunk.message.reasoning_content, "THINK"); } catch (_) {}
                  });
                }
              }

              if (chunk.done) {
                emitDone();
                return;
              }
            }
          }
          emitDone();
        } catch (e) {
          emitError(e);
        }
      })();

      return {
        promise,
        abort() {
          aborted = true;
          try { ctrl.abort(); } catch (_) {}
          finished = true;
        },
        onChunk(fn) { listeners.chunk.push(fn); return this; },
        onDone(fn)  { listeners.done.push(fn);  return this; },
        onError(fn) { listeners.error.push(fn); return this; },
      };
    }
  }

  window.BridgeApi = BridgeApi;
})();