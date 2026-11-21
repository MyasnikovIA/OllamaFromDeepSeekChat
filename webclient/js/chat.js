(function () {
  "use strict";

  // Ограничение на inline-передачу base64 в тексте.
  // Всё, что больше, шлём через /api/command {type:"file"}.
  const MAX_INLINE_BYTES = 2 * 1024 * 1024; // 2 MB

  class ChatController {
    constructor(api, els) {
      this.api = api;
      this.els = els;
      this.currentStream = null;
      this.busy = false;
      this.history = [];
    }

    // ---------- чтение файлов ----------
    async _readFiles(files) {
      const out = [];
      for (const f of files) {
        const b64 = await new Promise((resolve, reject) => {
          const r = new FileReader();
          r.onload = () => {
            const result = String(r.result || "");
            const comma = result.indexOf(",");
            resolve(comma >= 0 ? result.slice(comma + 1) : result);
          };
          r.onerror = () => reject(r.error || new Error("read error"));
          r.readAsDataURL(f);
        });
        out.push({
          name: f.name,
          mime: f.type || "application/octet-stream",
          size: f.size,
          path: f.webkitRelativePath || f.name,
          dataBase64: b64,
        });
      }
      return out;
    }

    // ---------- формирование маркеров ----------
    _formatFilesAsMarkers(attachments) {
      return attachments
          .filter(a => a.size <= MAX_INLINE_BYTES)
          .map(a =>
              `<!--DSFILE:BEGIN name=${encodeURIComponent(a.name)} ` +
              `mime=${a.mime} size=${a.size} ` +
              `path=${encodeURIComponent(a.path)} -->\n` +
              a.dataBase64 + "\n" +
              `<!--DSFILE:END-->`
          )
          .join("\n\n");
    }

    // ---------- большие файлы — через /api/command ----------
    async _sendLargeFiles(attachments) {
      for (const a of attachments) {
        if (a.size <= MAX_INLINE_BYTES) continue;
        try {
          // Серверу передаём путь + текст. Если файла на диске сервера
          // нет — вернёт ошибку, но base64 всё равно уйдёт в /api/chat
          await this.api.cmd("file", { path: a.path, text: "" });
        } catch (e) {
          console.warn("[chat] large file via /api/command failed:", e.message);
        }
      }
    }

    // ---------- отправка ----------
    async send(text, files = []) {
      if ((!text && files.length === 0) || this.busy) return;
      this.busy = true;

      if (this.els.emptyState) {
        this.els.emptyState.classList.add("hidden");
      }

      // 1. Читаем файлы
      let attachments = [];
      if (files && files.length > 0) {
        try {
          attachments = await this._readFiles(files);
        } catch (e) {
          console.error("[chat] read files failed:", e);
          this.busy = false;
          UI.appendMessage(this.els.messages, "Ошибка чтения файлов: " + e.message, "error");
          return;
        }
      }

      // 2. Для больших файлов — отправим на сервер отдельно
      if (attachments.some(a => a.size > MAX_INLINE_BYTES)) {
        this._sendLargeFiles(attachments).catch(() => {});
      }

      // 3. Маркеры для маленьких/средних
      const markers = this._formatFilesAsMarkers(attachments);
      const fullText = markers
          ? (text ? text + "\n\n" + markers : markers)
          : text;

      // 4. В историю — с маркерами (сервер их пробросит)
      this.history.push({ role: "user", content: fullText });

      // 5. В UI — только чистый текст + плашки файлов
      UI.appendMessage(
          this.els.messages,
          text || "",
          "outgoing",
          UI.nowTime(),
          attachments
      );

      // 6. Запускаем стрим
      const typing = UI.appendMessage(this.els.messages, "", "incoming", UI.nowTime());
      typing.textContent = "";
      UI.setTyping(typing, true);

      let accResponse = "";
      let accThought = "";
      let thoughtEl = null;

      const stream = this.api.chatStream(this.history);
      this.currentStream = stream;
      this._updateStopBtn(true);

      stream.onChunk((piece, type) => {
        if (type === "THINK") {
          accThought += piece;
          if (!thoughtEl) {
            thoughtEl = UI.appendMessage(this.els.messages, "", "thought", "");
            thoughtEl.textContent = "";
            this.els.messages.insertBefore(thoughtEl.parentNode, typing.parentNode);
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
        UI.setTyping(typing, false);
        if (!accResponse) UI.updateTypingText(typing, "(пустой ответ)");
        this.history.push({ role: "assistant", content: accResponse });
        this._finish();
        UI.initCopyButtons(typing);
        if (thoughtEl) UI.initCopyButtons(thoughtEl);
      });

      stream.onError((err) => {
        UI.setTyping(typing, false);
        if (!accResponse && !accThought) typing.parentNode.remove();
        UI.appendMessage(this.els.messages, "Ошибка: " + (err.message || err), "error");
        this._finish();
      });
    }

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

    _finish() {
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