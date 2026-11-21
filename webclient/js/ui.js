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

      // Кнопка копирования всего сообщения / размышления
      const copyBtn = document.createElement("button");
      copyBtn.className = "copy-btn";
      copyBtn.innerHTML = "📋";
      copyBtn.title = "Копировать";
      copyBtn.onclick = (e) => {
          e.stopPropagation();
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

      // 1. Извлекаем блоки ```...```, сохраняем их в массив и заменяем на плейсхолдеры
      const codeBlocks = [];
      let s = String(text).replace(/```(?:[a-zA-Z0-9_-]*\n)?([\s\S]*?)```/g, (match, code) => {
        const id = "code-" + codeBlocks.length;
        codeBlocks.push({ id, code });
        return `\u0000CODE_BLOCK_${codeBlocks.length - 1}\u0000`;
      });

      // 2. Экранируем HTML в оставшемся тексте
      s = esc(s);

      // 3. Инлайн-код (одинарные бэктики)
      s = s.replace(/`([^`\n]+)`/g, (_, c) => '<span class="msg-inline-code">' + c + "</span>");

      // 4. Возвращаем блоки кода с оберткой, содержащей кнопку копирования
      s = s.replace(/\u0000CODE_BLOCK_(\d+)\u0000/g, (_, i) => {
        const block = codeBlocks[Number(i)];
        const escapedCode = esc(block.code);
        // Кнопка копирования кода
        const copyBtn = `<button class="copy-code-btn" data-code="${encodeURIComponent(block.code)}">📋</button>`;
        return `<div class="code-container">${copyBtn}<pre class="msg-code">${escapedCode}</pre></div>`;
      });

      return s;
    },

    // Обработчик для динамически созданных кнопок копирования кода
    initCopyButtons(root) {
      root.querySelectorAll('.copy-code-btn').forEach(btn => {
        btn.onclick = (e) => {
          e.stopPropagation();
          const code = decodeURIComponent(btn.getAttribute('data-code') || '');
          navigator.clipboard.writeText(code);
          const originalHtml = btn.innerHTML;
          btn.innerHTML = "✅";
          setTimeout(() => btn.innerHTML = originalHtml, 1500);
        };
      });
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
      // Инициализируем кнопки копирования кода
      this.initCopyButtons(el);
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

      tabs.forEach((t, index) => {
        const li = document.createElement("li");
        li.className = "tab-item" + (t.tabId === activeTabId ? " active" : "");

        const avatar = document.createElement("div");
        avatar.className = "tab-avatar";
        avatar.textContent = "🟢";

        const meta = document.createElement("div");
        meta.className = "tab-meta";

        const id = document.createElement("div");
        id.className = "tab-id";
        id.textContent = `#${index} ${t.tabId.substring(0, 8)}...`;

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

        const closeBtn = document.createElement("button");
        closeBtn.className = "close-tab-btn";
        closeBtn.innerHTML = "✕";
        closeBtn.title = "Закрыть вкладку";
        closeBtn.onclick = (e) => {
            e.stopPropagation();
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
        li.className = "tab-item browser-item";
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

    getTabDisplayInfo(tabId, tabs, browsers) {
      const tabIndex = tabs.findIndex(t => t.tabId === tabId);
      if (tabIndex < 0) return `tab-${tabId.substring(0, 8)}`;
      const browser = browsers[tabIndex];
      const browserName = browser ? browser.displayName : "Browser";
      return `#${tabIndex} ${browserName} (${tabId.substring(0, 8)}...)`;
    },

    removeTabUI(tabId, tabContainers) {
      if (tabContainers[tabId]) {
        tabContainers[tabId].remove();
        delete tabContainers[tabId];
      }
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