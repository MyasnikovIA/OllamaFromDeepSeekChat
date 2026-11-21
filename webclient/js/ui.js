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
          .replace(/>/g, "&gt;")
          .replace(/"/g, "&quot;");
    },

    // ---------- убираем маркеры файлов из текста ----------
    // Оставлено для обратной совместимости: если сервер вернёт
    // старый формат с маркерами в тексте — они не попадут в UI.
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
        return `\u0000CODE_${codeBlocks.length - 1}\u0000`;
      });

      // 2. Инлайн-код `...` — выносим ДО bold/italic,
      //    чтобы `*x*` внутри кода не превращалось в <em>.
      const inlineCodes = [];
      html = html.replace(/`([^`\n]+)`/g, (_, code) => {
        inlineCodes.push(code);
        return `\u0000INLINE_${inlineCodes.length - 1}\u0000`;
      });

      // 3. Ссылки [text](url) — выносим до bold/italic.
      //    URL пропускаем только http(s)/mailto, чтобы не пускать javascript:.
      const links = [];
      html = html.replace(
          /\[([^\]]+)\]\((https?:\/\/[^)\s]+|mailto:[^)\s]+)\)/g,
          (_, text, url) => {
            links.push({ text, url });
            return `\u0000LINK_${links.length - 1}\u0000`;
          }
      );
      // «Голые» URL
      html = html.replace(
          /(^|[\s(])((?:https?:\/\/)[^\s<)]+)/g,
          (m, pre, url) => {
            links.push({ text: url, url });
            return `${pre}\u0000LINK_${links.length - 1}\u0000`;
          }
      );

      // 4. Strikethrough
      html = html.replace(/~~([^~\n]+)~~/g, "<del>$1</del>");

      // 5. Bold / italic
      html = html.replace(/\*\*\*([^*\n]+)\*\*\*/g, "<strong><em>$1</em></strong>");
      html = html.replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>");
      // *italic* только по границам слова, чтобы не ломать a*b*c
      html = html.replace(/(^|[^\w*])\*([^*\n]+)\*(?=[^\w*]|$)/g, "$1<em>$2</em>");
      // _italic_ только по границам слова — не трогает snake_case
      html = html.replace(/(^|[^\w])_([^_\n]+)_(?=[^\w]|$)/g, "$1<em>$2</em>");

      // 6. Заголовки
      html = html.replace(/^###### (.*)$/gim, "<h6>$1</h6>");
      html = html.replace(/^##### (.*)$/gim, "<h5>$1</h5>");
      html = html.replace(/^#### (.*)$/gim, "<h4>$1</h4>");
      html = html.replace(/^### (.*)$/gim, "<h3>$1</h3>");
      html = html.replace(/^## (.*)$/gim, "<h2>$1</h2>");
      html = html.replace(/^# (.*)$/gim, "<h1>$1</h1>");

      // 7. Списки
      html = html.replace(/^\s*[-*]\s+(.*)$/gim, "<li>$1</li>");
      html = html.replace(/(?:^|\n)((?:<li>.*<\/li>\n?)+)/g, (m, group) =>
          "<ul>" + group.trim() + "</ul>\n");

      // 8. Горизонтальная линия
      html = html.replace(/^---$/gim, "<hr>");

      // 9. Абзацы
      const lines = html.split("\n");
      const out = [];
      let inP = false;
      const blockTags = /^<(h\d|ul|li|hr|pre|div|del)/;
      for (const line of lines) {
        const t = line.trim();
        if (!t) {
          if (inP) { out.push("</p>"); inP = false; }
          continue;
        }
        if (blockTags.test(t) || t.startsWith("\u0000CODE_") || t === "</ul>") {
          if (inP) { out.push("</p>"); inP = false; }
          out.push(line);
        } else {
          if (!inP) { out.push("<p>"); inP = true; }
          out.push(line);
        }
      }
      if (inP) out.push("</p>");
      html = out.join("\n");

      // 10. Восстанавливаем ссылки
      html = html.replace(/\u0000LINK_(\d+)\u0000/g, (_, i) => {
        const l = links[Number(i)];
        if (!l) return "";
        return (
            `<a href="${this.esc(l.url)}" target="_blank" ` +
            `rel="noopener noreferrer">${this.esc(l.text)}</a>`
        );
      });

      // 11. Восстанавливаем инлайн-код
      html = html.replace(/\u0000INLINE_(\d+)\u0000/g, (_, i) => {
        const code = inlineCodes[Number(i)] || "";
        return `<span class="msg-inline-code">${this.esc(code)}</span>`;
      });

      // 12. Восстанавливаем блоки кода
      html = html.replace(/\u0000CODE_(\d+)\u0000/g, (_, i) => {
        const b = codeBlocks[Number(i)];
        if (!b) return "";
        const escaped = this.esc(b.code.replace(/^\n|\n$/g, ""));
        const lang = b.lang
            ? `<span class="code-lang">${this.esc(b.lang)}</span>`
            : "";
        return (
            `<div class="code-container">` +
            `<button class="copy-code-btn" ` +
            `data-code="${this.esc(encodeURIComponent(b.code))}" ` +
            `title="Копировать код">📋</button>` +
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
          }).catch(() => {});
        };
      });
    },

    // ---------- иконка по mime ----------
    mimeIcon(mime, name) {
      const m = (mime || "").toLowerCase();
      const n = (name || "").toLowerCase();
      if (m.startsWith("image/")) return "🖼";
      if (m.startsWith("audio/")) return "🎵";
      if (m.startsWith("video/")) return "🎬";
      if (m === "application/pdf" || n.endsWith(".pdf")) return "📕";
      if (/zip|rar|7z|tar|gz/.test(m) || /\.(zip|rar|7z|tar|gz)$/.test(n)) return "🗜";
      if (/json|xml|yaml/.test(m) || /\.(json|xml|yml|yaml)$/.test(n)) return "🧾";
      if (/javascript|typescript/.test(m) || /\.(js|ts|jsx|tsx)$/.test(n)) return "📜";
      if (/^text\//.test(m) || /\.(txt|md|log|csv)$/.test(n)) return "📄";
      return "📎";
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

        const icon = document.createElement("span");
        icon.className = "file-icon";
        icon.textContent = this.mimeIcon(att.mime, att.name);

        const name = document.createElement("span");
        name.className = "file-name";
        name.textContent = att.name || "file";

        const size = document.createElement("span");
        size.className = "file-size";
        size.textContent = att.size ? this.humanSize(att.size) : "";

        const hint = document.createElement("span");
        hint.className = "file-expand-hint";
        hint.textContent = att.content ? "▶" : "";

        bar.appendChild(icon);
        bar.appendChild(name);
        bar.appendChild(size);
        bar.appendChild(hint);

        if (att.content) {
          bar.onclick = (e) => {
            e.stopPropagation();
            const opened = item.classList.toggle("open");
            hint.textContent = opened ? "▼" : "▶";
          };
        } else {
          bar.classList.add("no-preview");
        }

        item.appendChild(bar);

        if (att.content) {
          const content = document.createElement("div");
          content.className = "file-content";
          content.textContent = att.content;

          const copy = document.createElement("button");
          copy.className = "file-copy-btn";
          copy.innerHTML = "📋";
          copy.title = "Копировать содержимое";
          copy.onclick = (e) => {
            e.stopPropagation();
            navigator.clipboard.writeText(att.content).then(() => {
              copy.innerHTML = "✅";
              setTimeout(() => (copy.innerHTML = "📋"), 1500);
            }).catch(() => {});
          };

          item.appendChild(content);
          item.appendChild(copy);
        }

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

    // ---------- бейдж количества файлов на 📎 ----------
    setAttachBadge(btn, count) {
      if (!btn) return;
      let badge = btn.querySelector(".attach-badge");
      if (count > 0) {
        if (!badge) {
          badge = document.createElement("span");
          badge.className = "attach-badge";
          btn.appendChild(badge);
        }
        badge.textContent = String(count);
        btn.classList.add("has-files");
      } else {
        if (badge) badge.remove();
        btn.classList.remove("has-files");
      }
    },

    // ---------- appendMessage ----------
    appendMessage(container, text, kind, meta, attachments) {
      const row = document.createElement("div");
      row.className = "message-row " + kind;

      const el = document.createElement("div");
      el.className = "message " + kind;
      if (kind === "thought") el.classList.add("thought");

      if (attachments && attachments.length > 0) {
        el.appendChild(this.renderAttachments(attachments));
      }

      const cleanText = this.stripFileMarkers(text || "");
      if (cleanText) {
        const textDiv = document.createElement("div");
        textDiv.className = "message-text";
        textDiv.innerHTML = this.parseMarkdown(cleanText);
        el.appendChild(textDiv);
      }

      const copyBtn = document.createElement("button");
      copyBtn.className = "copy-btn";
      copyBtn.innerHTML = "📋";
      copyBtn.title = "Копировать текст";
      copyBtn.onclick = (e) => {
        e.stopPropagation();
        navigator.clipboard.writeText(cleanText).then(() => {
          copyBtn.innerHTML = "✅";
          setTimeout(() => (copyBtn.innerHTML = "📋"), 1500);
        }).catch(() => {});
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