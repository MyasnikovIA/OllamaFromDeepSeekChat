(function () {
  "use strict";

  const UI = {
    $: (sel, root = document) => root.querySelector(sel),
    $$: (sel, root = document) => Array.from(root.querySelectorAll(sel)),

    show(el) { if (el) el.classList.remove("hidden"); },
    hide(el) { if (el) el.classList.add("hidden"); },

    // ---------- экранирование HTML ----------
    esc(s) {
      return String(s)
          .replace(/&/g, "&amp;")
          .replace(/</g, "&lt;")
          .replace(/>/g, "&gt;");
    },

    // ---------- убираем маркеры файлов из текста ----------
    stripFileMarkers(text) {
      return String(text || "")
          .replace(/<!--DSFILE:BEGIN[\s\S]*?<!--DSFILE:END-->/g, "")
          .replace(/\n{3,}/g, "\n\n")
          .trim();
    },

    // ---------- Markdown → HTML (без внешних библиотек) ----------
    parseMarkdown(text) {
      if (!text) return "";

      let html = this.esc(text);

      // 1. Блоки кода ```lang\n...\n```
      const codeBlocks = [];
      html = html.replace(/```([\w-]*)\n?([\s\S]*?)```/g, (_, lang, code) => {
        codeBlocks.push({ lang: lang || "", code });
        return `\u0000CODE_BLOCK_${codeBlocks.length - 1}\u0000`;
      });

      // 2. Заголовки
      html = html.replace(/^###### (.*)$/gim, "<h6>$1</h6>");
      html = html.replace(/^##### (.*)$/gim, "<h5>$1</h5>");
      html = html.replace(/^#### (.*)$/gim, "<h4>$1</h4>");
      html = html.replace(/^### (.*)$/gim, "<h3>$1</h3>");
      html = html.replace(/^## (.*)$/gim, "<h2>$1</h2>");
      html = html.replace(/^# (.*)$/gim, "<h1>$1</h1>");

      // 3. Жирный и курсив
      html = html.replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>");
      html = html.replace(/\*([^*\n]+)\*/g, "<em>$1</em>");
      html = html.replace(/_([^_\n]+)_/g, "<em>$1</em>");

      // 4. Инлайн-код
      html = html.replace(/`([^`\n]+)`/g, '<span class="msg-inline-code">$1</span>');

      // 5. Списки
      html = html.replace(/^\s*[-*]\s+(.*)$/gim, "<li>$1</li>");
      html = html.replace(/(?:^|\n)((?:<li>.*<\/li>\n?)+)/g, (m, group) =>
          "<ul>" + group.trim() + "</ul>\n");

      // 6. Горизонтальная линия
      html = html.replace(/^---$/gim, "<hr>");

      // 7. Абзацы
      const lines = html.split("\n");
      const out = [];
      let inP = false;
      const blockTags = /^<(h\d|ul|li|hr|pre|div)/;
      for (const line of lines) {
        const t = line.trim();
        if (!t) {
          if (inP) { out.push("</p>"); inP = false; }
          continue;
        }
        if (blockTags.test(t) || t.startsWith("\u0000CODE_BLOCK_") || t === "</ul>") {
          if (inP) { out.push("</p>"); inP = false; }
          out.push(line);
        } else {
          if (!inP) { out.push("<p>"); inP = true; }
          out.push(line);
        }
      }
      if (inP) out.push("</p>");
      html = out.join("\n");

      // 8. Восстанавливаем блоки кода
      html = html.replace(/\u0000CODE_BLOCK_(\d+)\u0000/g, (_, i) => {
        const b = codeBlocks[Number(i)];
        const escaped = this.esc(b.code.replace(/^\n|\n$/g, ""));
        const lang = b.lang ? `<span class="code-lang">${this.esc(b.lang)}</span>` : "";
        return (
            `<div class="code-container">` +
            `<button class="copy-code-btn" data-code="${encodeURIComponent(b.code)}" title="Копировать код">📋</button>` +
            (lang ? `<div class="code-header">${lang}</div>` : "") +
            `<pre class="msg-code">${escaped}</pre>` +
            `</div>`
        );
      });

      return html;
    },

    // ---------- инициализация кнопок копирования кода ----------
    initCopyButtons(root) {
      root.querySelectorAll(".copy-code-btn").forEach(btn => {
        btn.onclick = (e) => {
          e.stopPropagation();
          const code = decodeURIComponent(btn.getAttribute("data-code") || "");
          navigator.clipboard.writeText(code).then(() => {
            const orig = btn.innerHTML;
            btn.innerHTML = "✅";
            setTimeout(() => (btn.innerHTML = orig), 1500);
          });
        };
      });
    },

    // ---------- рендер вложений (плашки) ----------
    renderAttachments(attachments) {
      const wrap = document.createElement("div");
      wrap.className = "attached-files";

      attachments.forEach(att => {
        const item = document.createElement("div");
        item.className = "file-item";

        const bar = document.createElement("div");
        bar.className = "file-attachment";
        bar.innerHTML =
            `<span class="file-icon">📄</span>` +
            `<span class="file-name">${this.esc(att.name)}</span>` +
            (att.size
                ? `<span class="file-size">${this.humanSize(att.size)}</span>`
                : "") +
            `<span class="file-expand-hint">▶</span>`;

        bar.onclick = (e) => {
          e.stopPropagation();
          const opened = item.classList.toggle("open");
          const hint = bar.querySelector(".file-expand-hint");
          if (hint) hint.textContent = opened ? "▼" : "▶";
        };

        const content = document.createElement("div");
        content.className = "file-content";
        content.textContent = att.content || "";

        // Кнопка копирования содержимого
        const copy = document.createElement("button");
        copy.className = "file-copy-btn";
        copy.innerHTML = "📋";
        copy.title = "Копировать содержимое";
        copy.onclick = (e) => {
          e.stopPropagation();
          navigator.clipboard.writeText(att.content || "").then(() => {
            copy.innerHTML = "✅";
            setTimeout(() => (copy.innerHTML = "📋"), 1500);
          });
        };

        item.appendChild(bar);
        item.appendChild(content);
        item.appendChild(copy);
        wrap.appendChild(item);
      });

      return wrap;
    },

    humanSize(bytes) {
      if (!bytes) return "";
      const units = ["Б", "КБ", "МБ", "ГБ"];
      let n = bytes, i = 0;
      while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
      return n.toFixed(n >= 10 || i === 0 ? 0 : 1) + " " + units[i];
    },

    // ---------- appendMessage ----------
    appendMessage(container, text, kind, meta, attachments) {
      const row = document.createElement("div");
      row.className = "message-row " + kind;

      const el = document.createElement("div");
      el.className = "message " + kind;
      if (kind === "thought") el.classList.add("thought");

      // Плашки файлов (сверху)
      if (attachments && attachments.length > 0) {
        el.appendChild(this.renderAttachments(attachments));
      }

      // Текст через Markdown-парсер (маркеры вычищены)
      const cleanText = this.stripFileMarkers(text || "");
      if (cleanText) {
        const textDiv = document.createElement("div");
        textDiv.className = "message-text";
        textDiv.innerHTML = this.parseMarkdown(cleanText);
        el.appendChild(textDiv);
      }

      // Кнопка копирования всего сообщения
      const copyBtn = document.createElement("button");
      copyBtn.className = "copy-btn";
      copyBtn.innerHTML = "📋";
      copyBtn.title = "Копировать текст";
      copyBtn.onclick = (e) => {
        e.stopPropagation();
        navigator.clipboard.writeText(cleanText).then(() => {
          copyBtn.innerHTML = "✅";
          setTimeout(() => (copyBtn.innerHTML = "📋"), 1500);
        });
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

      this.initCopyButtons(el);
      return el;
    },

    // ---------- обновление в процессе стрима ----------
    updateTypingText(el, text) {
      if (!el) return;
      const meta = el.querySelector(".msg-meta");
      const copy = el.querySelector(".copy-btn");
      const att = el.querySelector(".attached-files");

      const cleanText = this.stripFileMarkers(text || "");
      const newHtml = this.parseMarkdown(cleanText);

      let textDiv = el.querySelector(".message-text");
      if (!textDiv) {
        textDiv = document.createElement("div");
        textDiv.className = "message-text";
        el.appendChild(textDiv);
      }
      textDiv.innerHTML = newHtml;

      if (att) el.insertBefore(att, el.firstChild);
      if (meta) el.appendChild(meta);
      if (copy) el.appendChild(copy);
      this.initCopyButtons(el);
    },

    setTyping(el, on) {
      if (!el) return;
      el.classList.toggle("typing", !!on);
    },

    nowTime() {
      const d = new Date();
      return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    },

    // ---------- вкладки ----------
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

    // ---------- браузеры ----------
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