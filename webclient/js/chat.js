(function () {
  "use strict";

  // ===== ИЗМЕНЕНО: лимиты согласованы с сервером =====
  // BridgeConstants.MAX_FILE_BYTES = 20 МБ, MAX_TOTAL_BYTES = 50 МБ
  const MAX_FILE_BYTES   = 20 * 1024 * 1024;
  const MAX_TOTAL_BYTES  = 50 * 1024 * 1024;

  // Превью содержимого для текстовых файлов (только для UI-плашек)
  const MAX_PREVIEW_BYTES = 200 * 1024;

  // Ограничение истории (в символах JSON), чтобы запрос не раздувался
  const MAX_HISTORY_CHARS = 100_000;

  // Watchdog: если стрим не завершился за это время — принудительный _finish()
  const STREAM_TIMEOUT_MS = 10 * 60 * 1000; // 10 минут

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
      } catch (_) {
        resolve("");
      }
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
    }

    // ----------------------------------------------------------
    //  Чтение файлов: base64 + метаданные + превью для текстовых
    // ----------------------------------------------------------
    async _readFiles(files) {
      const out = [];
      let total = 0;

      for (const f of files) {
        if (f.size > MAX_FILE_BYTES) {
          throw new Error(
              `Файл "${f.name}" слишком большой: ${UI.humanSize(f.size)} ` +
              `(макс ${UI.humanSize(MAX_FILE_BYTES)})`
          );
        }
        total += f.size;
        if (total > MAX_TOTAL_BYTES) {
          throw new Error(
              `Суммарный размер файлов превышает ${UI.humanSize(MAX_TOTAL_BYTES)}`
          );
        }

        const b64 = await readAsBase64(f);

        let preview = "";
        if (f.size <= MAX_PREVIEW_BYTES && isProbablyText(f)) {
          preview = await readAsText(f);
        }

        out.push({
          name: f.name,
          mime: f.type || "application/octet-stream",
          size: f.size,
          path: f.webkitRelativePath || f.name,
          dataBase64: b64,
          content: preview,   // только для UI-плашки
        });
      }
      return out;
    }

    // ----------------------------------------------------------
    //  Формирование content для сервера (OpenAI-формат)
    //
    //  includeBase64=true  — с base64 (только для ТЕКУЩЕГО запроса)
    //  includeBase64=false — только метаданные (для history)
    // ----------------------------------------------------------
    _buildUserContent(text, attachments, includeBase64) {
      const safeText = text || "";
      if (!attachments || attachments.length === 0) {
        return safeText;
      }

      const parts = [];
      if (safeText) {
        parts.push({ type: "text", text: safeText });
      }
      for (const a of attachments) {
        const file = { name: a.name, mimeType: a.mime };
        if (includeBase64) file.dataBase64 = a.dataBase64;
        parts.push({ type: "file", file });
      }
      return parts;
    }

    // ----------------------------------------------------------
    //  Обрезка истории, чтобы не гнать мегабайты на сервер
    // ----------------------------------------------------------
    _pruneHistory() {
      let total = 0;
      for (let i = this.history.length - 1; i >= 0; i--) {
        let len;
        try { len = JSON.stringify(this.history[i]).length; }
        catch (_) { len = 0; }
        total += len;
        // Всегда оставляем минимум 2 последних (user + assistant)
        if (total > MAX_HISTORY_CHARS && i < this.history.length - 2) {
          this.history = this.history.slice(i);
          return;
        }
      }
    }

    // ----------------------------------------------------------
    //  Отправка
    // ----------------------------------------------------------
    async send(text, files = []) {
      if ((!text && files.length === 0) || this.busy) return;

      // Показываем «пусто» только после успешного чтения файлов
      if (this.els.emptyState) {
        this.els.emptyState.classList.add("hidden");
      }

      // 1. Читаем файлы
      let attachments = [];
      if (files && files.length > 0) {
        try {
          attachments = await this._readFiles(files);
        } catch (e) {
          // Возвращаем пустое состояние, если оно было
          if (this.els.emptyState) this.els.emptyState.classList.remove("hidden");
          UI.appendMessage(this.els.messages, "Ошибка чтения файлов: " + e.message, "error");
          return;
        }
      }

      this.busy = true;

      // 2. Для сервера — content с base64 в ПОСЛЕДНЕМ сообщении
      const currentUserContent = this._buildUserContent(text, attachments, true);
      const messagesForServer = this.history
          .slice()                                       // копия
          .concat([{ role: "user", content: currentUserContent }]);

      // 3. В history — без base64 (для следующих запросов и UI)
      const historyContent = this._buildUserContent(text, attachments, false);
      this.history.push({ role: "user", content: historyContent });
      this._pruneHistory();

      // 4. UI: показываем ТОЛЬКО чистый текст + плашки файлов
      UI.appendMessage(
          this.els.messages,
          text || "",
          "outgoing",
          UI.nowTime(),
          attachments
      );

      // 5. Пустой «incoming» под стрим
      const typing = UI.appendMessage(this.els.messages, "", "incoming", UI.nowTime());
      typing.textContent = "";
      UI.setTyping(typing, true);

      let accResponse = "";
      let accThought = "";
      let thoughtEl = null;

      // 6. Запускаем стрим
      const stream = this.api.chatStream(messagesForServer);
      this.currentStream = stream;
      this._updateStopBtn(true);
      this._startWatchdog();

      stream.onChunk((piece, type) => {
        if (type === "THINK") {
          accThought += piece;
          if (!thoughtEl) {
            thoughtEl = UI.appendMessage(this.els.messages, "", "thought", "");
            thoughtEl.textContent = "";
            // Вставляем ПЕРЕД «incoming»
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

        // В history — без base64 (только текст ответа)
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
        UI.appendMessage(
            this.els.messages,
            "Ошибка: " + (err && err.message ? err.message : String(err)),
            "error"
        );
        this._finish();
      });
    }

    // ----------------------------------------------------------
    //  Watchdog: принудительно завершаем зависший стрим
    // ----------------------------------------------------------
    _startWatchdog() {
      this._stopWatchdog();
      this._watchdogTimer = setTimeout(() => {
        console.warn("[chat] stream watchdog timeout, aborting");
        if (this.currentStream) {
          try { this.currentStream.abort(); } catch (_) {}
        }
        this.busy = false;
        this.currentStream = null;
        this._updateStopBtn(false);
        UI.appendMessage(
            this.els.messages,
            "Стрим не завершился за 10 минут — прерван",
            "error"
        );
      }, STREAM_TIMEOUT_MS);
    }

    _stopWatchdog() {
      if (this._watchdogTimer) {
        clearTimeout(this._watchdogTimer);
        this._watchdogTimer = null;
      }
    }

    // ----------------------------------------------------------
    //  stop / resume / newChat
    // ----------------------------------------------------------
    async stop() {
      try {
        await this.api.stop();
        UI.appendMessage(this.els.messages, "⏹ Остановлено", "system");
      } catch (e) {
        UI.appendMessage(this.els.messages, "Стоп: " + e.message, "error");
      }
    }

    async resume() {
      try {
        await this.api.resume();
        UI.appendMessage(this.els.messages, "▶ Продолжение…", "system");
      } catch (e) {
        UI.appendMessage(this.els.messages, "Продолжить: " + e.message, "error");
      }
    }

    async newChat() {
      try {
        await this.api.newChat();

        if (this.currentStream) {
          try { this.currentStream.abort(); } catch (_) {}
          this.currentStream = null;
        }
        this._stopWatchdog();
        this.busy = false;
        this._updateStopBtn(false);

        this.history = [];
        this.els.messages.innerHTML = "";

        const el = document.createElement("div");
        el.className = "empty-state";
        el.innerHTML =
            '<div class="empty-icon">💬</div>' +
            '<div class="empty-title">Начните диалог</div>' +
            '<div class="empty-sub">Отправьте сообщение, чтобы DeepSeek ответил</div>';
        this.els.messages.appendChild(el);
        this.els.emptyState = el;

        if (this.els.composer) {
          this.els.composer.value = "";
          this.els.composer.style.height = "auto";
        }
      } catch (e) {
        UI.appendMessage(this.els.messages, "Новый чат: " + e.message, "error");
      }
    }

    // ----------------------------------------------------------
    //  helpers
    // ----------------------------------------------------------
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