(function () {
  "use strict";

  const MAX_FILE_BYTES   = 20 * 1024 * 1024;
  const MAX_TOTAL_BYTES  = 50 * 1024 * 1024;
  const MAX_PREVIEW_BYTES = 200 * 1024;
  const MAX_HISTORY_CHARS = 100_000;
  const STREAM_TIMEOUT_MS = 10 * 60 * 1000;

  const TEXT_MIME_RE = /^(text\/|application\/(json|xml|x-yaml|javascript|typescript))/i;
  const TEXT_EXT_RE  = /\.(txt|md|markdown|json|csv|tsv|log|xml|html?|js|mjs|ts|jsx|tsx|py|java|c|h|cpp|hpp|cs|go|rs|rb|php|css|scss|sass|yml|yaml|ini|cfg|conf|toml|sh|bat|ps1|sql|env)$/i;

  function isProbablyText(f) {
    if (f.type && TEXT_MIME_RE.test(f.type)) return true;
    if (TEXT_EXT_RE.test(f.name || "")) return true;
    return false;
  }

  function readAsBase64(file) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => {
        const result = String(r.result || "");
        const comma = result.indexOf(",");
        resolve(comma >= 0 ? result.slice(comma + 1) : result);
      };
      r.onerror = () => reject(r.error || new Error("read error: " + file.name));
      r.readAsDataURL(file);
    });
  }

  function readAsText(file) {
    return new Promise((resolve) => {
      try {
        const r = new FileReader();
        r.onload = () => resolve(String(r.result || ""));
        r.onerror = () => resolve("");
        r.readAsText(file);
      } catch (_) { resolve(""); }
    });
  }

  class ChatController {
    constructor(api, els) {
      this.api = api;
      this.els = els;
      this.currentStream = null;
      this.busy = false;
      this.history = [];
      this._watchdogTimer = null;
      this.strategy = "clipboard";
    }

    setStrategy(strategy) {
      if (["inline", "clipboard", "auto"].includes(strategy)) this.strategy = strategy;
    }

    async _readFile(f) {
      if (f.size > MAX_FILE_BYTES) {
        throw new Error(`Файл "${f.name}" слишком большой`);
      }
      const b64 = await readAsBase64(f);
      return {
        name: f.name, mime: f.type || "application/octet-stream",
        size: f.size, path: f.webkitRelativePath || f.name,
        dataBase64: b64, _isText: isProbablyText(f)
      };
    }

    // =========================================================
    //  Отправка ОДНОГО файла в плагин (без запуска чата)
    // =========================================================
    async uploadFileToPlugin(file) {
      try {
        const fileData = await this._readFile(file);
        await this.api.clipboardAttach([{
          name: fileData.name,
          mimeType: fileData.mime,
          dataBase64: fileData.dataBase64
        }]);
        UI.appendMessage(this.els.messages, `📋 Файл "${file.name}" вставлен в DeepSeek`, "system");
      } catch (e) {
        UI.appendMessage(this.els.messages, "Ошибка чтения/вставки файла: " + e.message, "error");
        throw e;
      }
    }

    // =========================================================
    //  Отправка финального запроса (текст + неотправленные файлы)
    //  Порядок на стороне плагина: setText → attachFiles → clickSend
    // =========================================================
    async send(text, pendingFiles = []) {
      if ((!text && pendingFiles.length === 0) || this.busy) return;

      // 1. Читаем все неотправленные файлы в base64
      const filesData = [];
      for (const file of pendingFiles) {
        try {
          const fd = await this._readFile(file);
          filesData.push({
            name: fd.name,
            mimeType: fd.mime,
            dataBase64: fd.dataBase64,
          });
        } catch (e) {
          UI.appendMessage(this.els.messages,
              `Ошибка чтения "${file.name}": ${e.message}`, "error");
          return; // не отправляем, если файл не прочитался
        }
      }

      const messagesForServer = this.history.slice().concat([{ role: "user", content: text }]);
      this.history.push({ role: "user", content: text });
      this._pruneHistory();

      // skipAttach: false — плагин сам вставит файлы перед отправкой.
      // appendText: false — текст заменяет содержимое поля ввода.
      await this._doStream(messagesForServer, text, {
        files: filesData,
        skipAttach: false,
        appendText: false,
      });
    }

    async _doStream(messagesForServer, outgoingText, opts = {}) {
      this.busy = true;
      if (this.els.emptyState) this.els.emptyState.classList.add("hidden");

      UI.appendMessage(this.els.messages, outgoingText || "", "outgoing", UI.nowTime());
      const typing = UI.appendMessage(this.els.messages, "", "incoming", UI.nowTime());
      typing.textContent = "";
      UI.setTyping(typing, true);

      let accResponse = "";
      let accThought = "";
      let thoughtEl = null;

      const stream = this.api.chatStream(messagesForServer, {
        skipAttach: !!opts.skipAttach,
        appendText: !!opts.appendText,
        files: opts.files || [],   // ← новое: передаём файлы в /api/chat
      });

      this.currentStream = stream;
      this._updateStopBtn(true);
      this._startWatchdog();

      stream.onChunk((piece, type) => {
        if (type === "THINK") {
          accThought += piece;
          if (!thoughtEl) {
            thoughtEl = UI.appendMessage(this.els.messages, "", "thought", "");
            thoughtEl.textContent = "";
            const row = thoughtEl.parentNode;
            if (typing.parentNode && typing.parentNode.parentNode === this.els.messages) {
              this.els.messages.insertBefore(row, typing.parentNode);
            }
          }
          UI.updateTypingText(thoughtEl, accThought);
        } else {
          accResponse += piece;
          UI.updateTypingText(typing, accResponse);
          UI.setTyping(typing, true);
        }
        this.els.messages.scrollTop = this.els.messages.scrollHeight;
      });

      stream.onDone(() => {
        this._stopWatchdog();
        UI.setTyping(typing, false);
        if (!accResponse) UI.updateTypingText(typing, "(пустой ответ)");
        this.history.push({ role: "assistant", content: accResponse });
        this._pruneHistory();
        this._finish();
        UI.initCopyButtons(typing);
        if (thoughtEl) UI.initCopyButtons(thoughtEl);
      });

      stream.onError((err) => {
        this._stopWatchdog();
        UI.setTyping(typing, false);
        if (!accResponse && !accThought) {
          const row = typing.parentNode;
          if (row && row.parentNode) row.parentNode.removeChild(row);
        }
        UI.appendMessage(this.els.messages, "Ошибка: " + (err && err.message ? err.message : String(err)), "error");
        this._finish();
      });
    }

    _pruneHistory() {
      let total = 0;
      for (let i = this.history.length - 1; i >= 0; i--) {
        let len = 0;
        try { len = JSON.stringify(this.history[i]).length; } catch (_) {}
        total += len;
        if (total > MAX_HISTORY_CHARS && i < this.history.length - 2) {
          this.history = this.history.slice(i);
          return;
        }
      }
    }

    _startWatchdog() {
      this._stopWatchdog();
      this._watchdogTimer = setTimeout(() => {
        if (this.currentStream) { try { this.currentStream.abort(); } catch (_) {} }
        this.busy = false;
        this.currentStream = null;
        this._updateStopBtn(false);
        UI.appendMessage(this.els.messages, "Стрим не завершился за 10 минут — прерван", "error");
      }, STREAM_TIMEOUT_MS);
    }

    _stopWatchdog() {
      if (this._watchdogTimer) { clearTimeout(this._watchdogTimer); this._watchdogTimer = null; }
    }

    _finish() {
      this._stopWatchdog();
      this.busy = false;
      this.currentStream = null;
      this._updateStopBtn(false);
    }

    _updateStopBtn(show) {
      if (!this.els.stopBtn) return;
      if (show) UI.show(this.els.stopBtn);
      else      UI.hide(this.els.stopBtn);
    }
  }

  window.ChatController = ChatController;
})();