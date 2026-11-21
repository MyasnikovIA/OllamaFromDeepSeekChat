(function () {
  "use strict";

  const UI = {
    $: (sel, root = document) => root.querySelector(sel),
    $$: (sel, root = document) => Array.from(root.querySelectorAll(sel)),

    show(el) { if (el) el.classList.remove("hidden"); },
    hide(el) { if (el) el.classList.add("hidden"); },

    esc(s) {
      return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    },

    // Основной метод парсинга Markdown
    parseMarkdown(text) {
      if (!text) return "";

      let html = this.esc(text);

      // 1. Блоки кода (```...```)
      const codeBlocks = [];
      html = html.replace(/```([\w-]*)\n([\s\S]*?)```/g, (match, lang, code) => {
        const id = "code-" + codeBlocks.length;
        codeBlocks.push({ id, lang, code });
        return `\u0000CODE_BLOCK_${codeBlocks.length - 1}\u0000`;
      });

      // 2. Заголовки
      html = html.replace(/^###### (.*$)/gim, '<h6>$1</h6>');
      html = html.replace(/^##### (.*$)/gim, '<h5>$1</h5>');
      html = html.replace(/^#### (.*$)/gim, '<h4>$1</h4>');
      html = html.replace(/^### (.*$)/gim, '<h3>$1</h3>');
      html = html.replace(/^## (.*$)/gim, '<h2>$1</h2>');
      html = html.replace(/^# (.*$)/gim, '<h1>$1</h1>');

      // 3. Жирный и курсив
      html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
      html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
      html = html.replace(/_([^_]+)_/g, '<em>$1</em>');

      // 4. Инлайн-код (`...`)
      html = html.replace(/`([^`\n]+)`/g, '<span class="msg-inline-code">$1</span>');

      // 5. Списки (- или *)
      html = html.replace(/^\s*[-*]\s+(.*$)/gim, '<li>$1</li>');
      html = html.replace(/(<li>.*<\/li>)/gis, '<ul>$1</ul>');

      // 6. Горизонтальная линия
      html = html.replace(/^---$/gim, '<hr>');

      // 7. Абзацы (оборачиваем оставшийся текст)
      // Примечание: это упрощенная логика, чтобы не ломать уже созданные теги
      const lines = html.split('\n');
      let inParagraph = false;
      const result = [];

      for (let line of lines) {
        const trimmed = line.trim();
        if (!trimmed) {
          if (inParagraph) { result.push('</p>'); inParagraph = false; }
        } else if (trimmed.startsWith('<h') || trimmed.startsWith('<ul>') ||
                   trimmed.startsWith('<li>') || trimmed.startsWith('<hr>') ||
                   trimmed.startsWith('\u0000CODE_BLOCK_') || trimmed === '</ul>') {
          if (inParagraph) { result.push('</p>'); inParagraph = false; }
          result.push(line);
        } else {
          if (!inParagraph) { result.push('<p>'); inParagraph = true; }
          result.push(line);
        }
      }
      if (inParagraph) result.push('</p>');
      html = result.join('\n');

      // 8. Восстанавливаем блоки кода
      html = html.replace(/\u0000CODE_BLOCK_(\d+)\u0000/g, (_, i) => {
        const block = codeBlocks[Number(i)];
        const escapedCode = this.esc(block.code);
        return `<div class="code-container"><button class="copy-code-btn" data-code="${encodeURIComponent(block.code)}">📋</button><pre class="msg-code">${escapedCode}</pre></div>`;
      });

      return html;
    },

    appendMessage(container, text, kind, meta, attachments) {
      const row = document.createElement("div");
      row.className = "message-row " + kind;

      const el = document.createElement("div");
      el.className = "message " + kind;
      if (kind === "thought") el.classList.add("thought");

      // Вложения (файлы)
      if (attachments && attachments.length > 0) {
        const attDiv = document.createElement("div");
        attDiv.className = "attached-files";
        attachments.forEach(att => {
          const btn = document.createElement("div");
          btn.className = "file-attachment";
          btn.innerHTML = `<span class="file-icon">📄</span><span class="file-name">${this.esc(att.name)}</span><span class="file-expand-hint">▶</span>`;
          btn.onclick = (e) => {
            e.stopPropagation();
            btn.classList.toggle("expanded");
            const hint = btn.querySelector(".file-expand-hint");
            if (hint) hint.textContent = btn.classList.contains("expanded") ? "▼" : "▶";
          };
          attDiv.appendChild(btn);

          const content = document.createElement("div");
          content.className = "file-content";
          content.textContent = att.content;
          attDiv.appendChild(content);
        });
        el.appendChild(attDiv);
      }

      // Текст сообщения (через Markdown-парсер)
      const textDiv = document.createElement("div");
      textDiv.innerHTML = this.parseMarkdown(text);
      el.appendChild(textDiv);

      // Кнопка копирования
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

      // Инициализируем кнопки копирования кода
      this.initCopyButtons(el);

      return el;
    },

    initCopyButtons(root) {
      root.querySelectorAll('.copy-code-btn').forEach(btn => {
        btn.onclick = (e) => {
          e.stopPropagation();
          const code = decodeURIComponent(btn.getAttribute('data-code') || '');
          navigator.clipboard.writeText(code);
          const orig = btn.innerHTML;
          btn.innerHTML = "✅";
          setTimeout(() => btn.innerHTML = orig, 1500);
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
      const att = el.querySelector(".attached-files");
      const textDiv = el.querySelector("div:not(.attached-files)");

      const newHtml = this.parseMarkdown(text);
      if (textDiv) {
        textDiv.innerHTML = newHtml;
      } else {
        el.innerHTML = newHtml;
      }

      if (att) el.insertBefore(att, el.firstChild);
      if (meta) el.appendChild(meta);
      if (copy) el.appendChild(copy);
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