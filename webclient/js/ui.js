(function () {
  "use strict";

  const UI = {
    $: (sel, root = document) => root.querySelector(sel),
    $$: (sel, root = document) => Array.from(root.querySelectorAll(sel)),

    show(el) { if (el) el.classList.remove("hidden"); },
    hide(el) { if (el) el.classList.add("hidden"); },

    appendMessage(container, text, kind, meta) {
      const row = document.createElement("div");
      row.className = "message-row " + kind;

      const el = document.createElement("div");
      el.className = "message " + kind;
      if (kind === "thought") el.classList.add("thought");

      el.innerHTML = this.formatMessageText(text);

      const copyBtn = document.createElement("button");
      copyBtn.className = "copy-btn";
      copyBtn.innerHTML = "📋";
      copyBtn.title = "Копировать";
      copyBtn.onclick = () => {
          navigator.clipboard.writeText(text);
          copyBtn.innerHTML = "✅";
          setTimeout(() => copyBtn.innerHTML = "📋", 1500);
      };
      el.appendChild(copyBtn);

      if (meta) {
        const m = document.createElement("span");
        m.className = "msg-meta";
        m.textContent = meta;
        el.appendChild(m);
      }

      row.appendChild(el);
      container.appendChild(row);
      container.scrollTop = container.scrollHeight;
      return el;
    },

    formatMessageText(text) {
      const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
      const blocks = [];
      let s = String(text).replace(/```([\s\S]*?)```/g, (_, code) => {
        blocks.push(code);
        return "\u0000B" + (blocks.length - 1) + "\u0000";
      });
      s = esc(s);
      s = s.replace(/`([^`\n]+)`/g, (_, c) => '<span class="msg-inline-code">' + c + "</span>");
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
      const meta = el.querySelector(".msg-meta");
      const copy = el.querySelector(".copy-btn");
      el.innerHTML = this.formatMessageText(text);
      if (meta) el.appendChild(meta);
      if (copy) el.appendChild(copy);
    },

    nowTime() {
      const d = new Date();
      return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    },

    renderTabs(listEl, tabs, activeTabId, onSelect, onClose) {
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

        // Кнопка закрытия (крестик)
        const closeBtn = document.createElement("button");
        closeBtn.className = "close-tab-btn";
        closeBtn.innerHTML = "✕";
        closeBtn.title = "Закрыть вкладку";
        closeBtn.onclick = (e) => {
            e.stopPropagation(); // Предотвращаем выбор вкладки
            onClose(t.tabId);
        };
        li.appendChild(closeBtn);

        li.title = t.tabId + (t.info ? "\n" + t.info : "");
        li.addEventListener("click", () => onSelect(t.tabId));
        listEl.appendChild(li);
      });
    },

    renderBrowsers(listEl, browsers, onSelect) {
      listEl.innerHTML = "";
      if (!browsers || browsers.length === 0) {
        const empty = document.createElement("li");
        empty.className = "tab-empty";
        empty.textContent = "Нет доступных профилей";
        listEl.appendChild(empty);
        return;
      }

      browsers.forEach((b) => {
        const li = document.createElement("li");
        li.className = "tab-item";
        li.title = b.userDataDir;

        const avatar = document.createElement("div");
        avatar.className = "tab-avatar";
        avatar.textContent = "🌐";

        const meta = document.createElement("div");
        meta.className = "tab-meta";

        const id = document.createElement("div");
        id.className = "tab-id";
        id.textContent = `#${b.index} ${b.displayName}`;

        const flags = document.createElement("div");
        flags.className = "tab-flags";
        const flag = document.createElement("span");
        flag.className = "tab-flag " + (b.valid ? "on" : "off");
        flag.textContent = b.valid ? "OK" : "Not Found";
        flags.appendChild(flag);

        meta.appendChild(id);
        meta.appendChild(flags);

        li.appendChild(avatar);
        li.appendChild(meta);

        li.addEventListener("click", () => onSelect(b.index));
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

    setModeCheckboxes(thinkEl, searchEl, { thinking, smartSearch }) {
      if (thinkEl)  thinkEl.checked  = !!thinking;
      if (searchEl) searchEl.checked = !!smartSearch;
    },
  };

  window.UI = UI;
})();