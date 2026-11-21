/**
 * Клиент bridge API.
 * Оборачивает все запросы к серверу: /api/command, /api/chat, /tabs.
 * Возвращает промисы, бросает Error при ok:false.
 */
(function () {
  "use strict";

  class BridgeApi {
    constructor(baseUrl) {
      this.baseUrl = (baseUrl || "").replace(/\/+$/, "");
    }

    // ---------- низкоуровневый fetch с JSON ----------
    async _json(method, path, body) {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(new Error("request timeout")), 130000);
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

        if (!resp.ok && !data.ok) {
          throw new Error(data.error || ("HTTP " + resp.status));
        }
        return data;
      } finally {
        clearTimeout(timer);
      }
    }

    // ---------- высокоуровневый cmd ----------
    async cmd(type, extra = {}, opts = {}) {
      const data = await this._json("POST", "/api/command", { type, ...extra });
      if (!data.ok) throw new Error(data.error || "unknown error");
      return data.result;
    }

    // ---------- проверка живости ----------
    async ping() {
      const resp = await fetch(this.baseUrl + "/", { method: "GET" });
      if (!resp.ok) throw new Error("server ping failed: HTTP " + resp.status);
      return true;
    }

    // ---------- вкладки ----------
    async tabs() {
      const result = await this.cmd("tabs");
      return Array.isArray(result.tabs) ? result.tabs : [];
    }

    async selectTab(tabId) {
      return this.cmd("tab", { tabId });
    }

    async state() {
      return this.cmd("state");
    }

    // ---------- режимы ----------
    async setThinking(on) {
      return this.cmd(on ? "enableThinking" : "disableThinking");
    }
    async setSearch(on) {
      return this.cmd(on ? "enableSmartSearch" : "disableSmartSearch");
    }

    // ---------- действия ----------
    async newChat()   { return this.cmd("newChat"); }
    async stop()      { return this.cmd("stopGeneration"); }
    async resume()    { return this.cmd("resumeGeneration"); }

    // ---------- чат: поток через /api/chat ----------
    chatStream(text, { signal } = {}) {
      const url = this.baseUrl + "/api/chat";
      const body = {
        model: "DeepSeekCloud",
        messages: [{ role: "user", content: text }],
        stream: true,
      };

      const listeners = { chunk: [], done: [], error: [] };
      let aborted = false;
      const ctrl = new AbortController();
      if (signal) signal.addEventListener("abort", () => { aborted = true; ctrl.abort(); }, { once: true });

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
          if (!aborted) listeners.error.forEach(fn => fn(e));
          return;
        }

        if (!resp.ok) {
          let errText = "HTTP " + resp.status;
          try { const j = await resp.json(); if (j.error) errText = j.error; } catch (_) {}
          listeners.error.forEach(fn => fn(new Error(errText)));
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
            while ((idx = buf.indexOf("\n")) !== -1) {
              const line = buf.slice(0, idx).trim();
              buf = buf.slice(idx + 1);
              if (!line) continue;
              let chunk;
              try { chunk = JSON.parse(line); } catch (_) { continue; }
              if (chunk.error) {
                listeners.error.forEach(fn => fn(new Error(chunk.error)));
                return;
              }
              if (chunk.message && chunk.message.content) {
                listeners.chunk.forEach(fn => fn(chunk.message.content, "RESPONSE"));
              }
              if (chunk.done) {
                listeners.done.forEach(fn => fn());
                return;
              }
            }
          }
          listeners.done.forEach(fn => fn());
        } catch (e) {
          if (!aborted) listeners.error.forEach(fn => fn(e));
        }
      })();

      return {
        promise,
        abort() { aborted = true; ctrl.abort(); },
        onChunk(fn) { listeners.chunk.push(fn); return this; },
        onDone(fn)  { listeners.done.push(fn);  return this; },
        onError(fn) { listeners.error.push(fn); return this; },
      };
    }
  }

  window.BridgeApi = BridgeApi;
})();