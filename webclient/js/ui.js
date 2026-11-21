/**
 * UI-хелперы: работа с DOM, отрисовка вкладок, режимов,
 * сообщений в стиле WhatsApp.
 */
(function () {
  "use strict";

  const UI = {
    // ---------- DOM ----------
    $: (sel, root = document) => root.querySelector(sel),
    $$: (sel, root = document) => Array.from(root.querySelectorAll(sel)),

    show(el) { if (el) el.classList.remove("hidden"); },
    hide(el) { if (el) el.classList.add("hidden"); },

    // ---------- сообщения ----------
    appendMessage(container, text, kind, meta) {
      const el = document.createElement("div");
      el.className = "message " + kind;
      el.innerHTML = this.formatMessageText(text);
      if (meta) {
        const m = document.createElement("span");
        m.className = "msg-meta";
        m.textContent = meta;
        el.appendChild(m);
      }
      container.appendChild(el);
      container.scrollTop = container.scrollHeight;
      return el;
    },

    /**
     * Очень лёгкий форматтер: `code`, ```blocks```, переносы строк.
     * Никакого HTML из пользовательского текста не пропускаем —
     * сначала escape, потом разметка.
     */
    formatMessageText(text) {
      const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

      // Сначала вытащим ```...``` блоки во временные плейсхолдеры
      const blocks = [];
      let s = String(text).replace(/```([\s\S]*?)```/g, (_, code) => {
        blocks.push(code);
        return "\u0000B" + (blocks.length - 1) + "\u0000";
      });

      s = esc(s);

      // инлайн-код
      s = s.replace(/`([^`\n]+)`/g, (_, c) => '<span class="msg-inline-code">' + c + "</span>");

      // вернуть блоки как <pre>
      s = s.replace(/\u0000B(\d+)\u0000/g, (_, i) => {
        return '<span class="msg-code">' + esc(blocks[Number(i)]) + "</span>";
      });

      return s;
    },

    setTyping(el, on) {
      if (!el) return;
      el.classList.toggle("typing", !!on);
    },

    updateTypingText(el, text) {
      if (!el) return;
      // сохраняем meta, если есть
      const meta = el.querySelector(".msg-meta");
      el.innerHTML = this.formatMessageText(text);
      if (meta) el.appendChild(meta);
    },

    nowTime() {
      const d = new Date();
      return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    },

    // ---------- вкладки ----------
    renderTabs(listEl, tabs, activeTabId, onSelect) {
      listEl.innerHTML = "";
      if (!tabs || tabs.length === 0) {
        const empty = document.createElement("li");
        empty.className = "tab-empty";
        empty.textContent = "Нет активных вкладок DeepSeek";
        listEl.appendChild(empty);
        return;
      }

      tabs.forEach((t) => {
        const li = document.createElement("li");
        li.className = "tab-item" + (t.tabId === activeTabId ? " active" : "");

        const avatar = document.createElement("div");
        avatar.className = "tab-avatar";
        avatar.textContent = "🟢";

        const meta = document.createElement("div");
        meta.className = "tab-meta";

        const id = document.createElement("div");
        id.className = "tab-id";
        id.textContent = t.tabId || "(unknown)";

        const flags = document.createElement("div");
        flags.className = "tab-flags";
        flags.appendChild(this._flag("🧠", t.thinking));
        flags.appendChild(this._flag("🔍", t.smartSearch));
        if (t.tokenExhausted) {
          const f = document.createElement("span");
          f.className = "tab-flag off";
          f.textContent = "⚠ limit";
          flags.appendChild(f);
        }

        meta.appendChild(id);
        meta.appendChild(flags);

        li.appendChild(avatar);
        li.appendChild(meta);
        li.title = t.tabId + (t.info ? "\n" + t.info : "");

        li.addEventListener("click", () => onSelect(t.tabId));
        listEl.appendChild(li);
      });
    },

    _flag(icon, on) {
      const s = document.createElement("span");
      s.className = "tab-flag " + (on ? "on" : "off");
      s.textContent = icon;
      return s;
    },

    setStatus(pillEl, ok, text) {
      if (!pillEl) return;
      pillEl.classList.toggle("ok", !!ok);
      pillEl.classList.toggle("err", !ok);
      const t = pillEl.querySelector(".status-text");
      if (t) t.textContent = text;
    },

    // ---------- режимы ----------
    setModeCheckboxes(thinkEl, searchEl, { thinking, smartSearch }) {
      if (thinkEl)  thinkEl.checked  = !!thinking;
      if (searchEl) searchEl.checked = !!smartSearch;
    },
  };

  window.UI = UI;
})();