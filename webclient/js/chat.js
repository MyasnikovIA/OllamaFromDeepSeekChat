(function () {
  "use strict";

  class ChatController {
    constructor(api, els) {
      this.api = api;
      this.els = els;
      this.currentStream = null;
      this.busy = false;
      this.history = [];
    }

    async send(text) {
      if (!text || this.busy) return;
      this.busy = true;

      if (this.els.emptyState) {
        this.els.emptyState.classList.add("hidden");
      }

      this.history.push({ role: "user", content: text });
      UI.appendMessage(this.els.messages, text, "outgoing", UI.nowTime());

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
        // Инициализация кнопок копирования кода после завершения стрима
        UI.initCopyButtons(typing);
        if (thoughtEl) UI.initCopyButtons(thoughtEl);
      });

      stream.onError((err) => {
        UI.setTyping(typing, false);
        if (!accResponse && !accThought) {
            typing.parentNode.remove();
        }
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