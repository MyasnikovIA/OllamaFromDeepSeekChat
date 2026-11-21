/**
 * Управление текущим диалогом: отправка, стрим, стоп/продолжить,
 * новый чат (с полной очисткой UI).
 * Работает со ссылками на DOM-узлы, переданными из app.js.
 */
(function () {
  "use strict";

  class ChatController {
    constructor(api, els) {
      this.api = api;
      this.els = els;
      this.currentStream = null;
      this.busy = false;
      this.lastUserText = "";
    }

    // ---------- отправка ----------
    async send(text) {
      if (!text || this.busy) return;
      this.busy = true;
      this.lastUserText = text;

      if (this.els.emptyState) UI.hide(this.els.emptyState);

      UI.appendMessage(this.els.messages, text, "outgoing", UI.nowTime());

      const typing = UI.appendMessage(this.els.messages, "", "incoming", UI.nowTime());
      typing.textContent = "";
      UI.setTyping(typing, true);

      let acc = "";

      const stream = this.api.chatStream(text);
      this.currentStream = stream;

      this._updateStopBtn(true);

      stream.onChunk((piece) => {
        acc += piece;
        UI.updateTypingText(typing, acc);
        UI.setTyping(typing, true);
        this.els.messages.scrollTop = this.els.messages.scrollHeight;
      });

      stream.onDone(() => {
        UI.setTyping(typing, false);
        if (!acc) {
          UI.updateTypingText(typing, "(пустой ответ)");
        }
        this._finish();
      });

      stream.onError((err) => {
        UI.setTyping(typing, false);
        if (!acc) typing.remove();
        UI.appendMessage(
          this.els.messages,
          "Ошибка: " + (err.message || err),
          "error"
        );
        this._finish();
      });
    }

    // ---------- стоп ----------
    async stop() {
      try {
        await this.api.stop();
        UI.appendMessage(this.els.messages, "⏹ Остановлено", "system");
      } catch (e) {
        UI.appendMessage(this.els.messages, "Стоп: " + e.message, "error");
      }
    }

    // ---------- продолжить ----------
    async resume() {
      try {
        await this.api.resume();
        UI.appendMessage(this.els.messages, "▶ Продолжение…", "system");
      } catch (e) {
        UI.appendMessage(this.els.messages, "Продолжить: " + e.message, "error");
      }
    }

    // ---------- новый чат (полная очистка UI) ----------
    async newChat() {
      try {
        await this.api.newChat();

        // прервать текущий стрим, если был
        if (this.currentStream) {
          try { this.currentStream.abort(); } catch (_) {}
          this.currentStream = null;
        }
        this.busy = false;
        this._updateStopBtn(false);

        // очистить сообщения
        this.els.messages.innerHTML = "";

        // пересоздать пустое состояние
        const el = document.createElement("div");
        el.className = "empty-state";
        el.innerHTML =
          '<div class="empty-icon">💬</div>' +
          '<div class="empty-title">Начните диалог</div>' +
          '<div class="empty-sub">Отправьте сообщение, чтобы DeepSeek ответил</div>';
        this.els.messages.appendChild(el);
        this.els.emptyState = el;

        // очистить composer
        if (this.els.composer) {
          this.els.composer.value = "";
          this.els.composer.style.height = "auto";
        }

        UI.appendMessage(this.els.messages, "🆕 Новый чат", "system");
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