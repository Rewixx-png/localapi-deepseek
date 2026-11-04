/* Local API DeepSeek — UI controller.
 * All network, storage and streaming work happens in Rust; this file only
 * renders state and polls the native stream snapshot. */

(function () {
  "use strict";

  var NATIVE_COMPOSER = !!(window.Native && window.Native.setComposerVisible);
  if (window.Native) window.Android = window.Native;
  if (NATIVE_COMPOSER) document.body.classList.add("native-composer");

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  var S = {
    status: null,
    chats: [],
    chat: null,
    stream: null,
    timer: null,
    effort: localStorage.getItem("effort") || "high",
    model: localStorage.getItem("model") || "deepseek-flash",
    thinking: localStorage.getItem("thinking") !== "0",
    search: localStorage.getItem("search") === "1",
    theme: localStorage.getItem("theme") || "system",
    textZoom: Math.max(85, Math.min(150, parseInt(localStorage.getItem("textZoom") || "100", 10))),
    appliedZoom: 0,
    cfMode: localStorage.getItem("cfMode") === "token" ? "token" : "quick",
    compact: localStorage.getItem("compact") === "1",
    server: { running: false, port: 0, key: "", base_url: "" },
    online: true,
    settingsReturn: null,
    settingsPane: null,
    stick: true,
    lastScroll: 0,
    authMode: localStorage.getItem("authMode") || "api",
    version: "",
    update: null,
    updateBusy: false,
    streamEl: null,
    streamContent: null,
    streamReasoning: null,
    streamStage: null,
    lastContent: "",
    lastReasoning: ""
  };

  // ── native bridge ────────────────────────────────────────────────────
  // Explicit thunks: Android's JavaBridge methods are not safe to call through
  // dynamic lookup/apply, so every native call is spelled out once.
  var CALLS = {
    status: function () { return Native.status(); },
    setToken: function (t, mode) { return Native.setToken(t, mode); },
    revalidate: function () { return Native.revalidate(); },
    clearToken: function () { return Native.clearToken(); },
    listChats: function () { return Native.listChats(); },
    getChat: function (id) { return Native.getChat(id); },
    deleteChat: function (id) { return Native.deleteChat(id); },
    send: function (chatId, text, model, effort, thinking, search, imagesJson) {
      return Native.send(chatId, text, model, effort, thinking, search, imagesJson);
    },
    startServer: function (port) { return Native.startServer(port); },
    stopServer: function () { return Native.stopServer(); },
    serverStatus: function () { return Native.serverStatus(); },
    startTunnel: function (mode, token, host, port) { return Native.startTunnel(mode, token, host, port); },
    ensureService: function (port) { return Native.ensureService(port); },
    stopTunnel: function () { return Native.stopTunnel(); },
    tunnelStatus: function () { return Native.tunnelStatus(); },
    setOnline: function (online) { return Native.setOnline(online); },
    poll: function (id) { return Native.poll(id); },
    cancel: function (id) { return Native.cancel(id); },
    permissions: function () { return Native.permissions(); },
    requestNotifications: function () { return Native.requestNotifications(); },
    requestBattery: function () { return Native.requestBattery(); },
    readClipboard: function () { return Native.readClipboard(); },
    appInfo: function () { return Native.appInfo(); }
  };

  function invoke(name) {
    if (!window.Native || !CALLS[name]) {
      throw new Error("Мост приложения недоступен");
    }
    var args = Array.prototype.slice.call(arguments, 1);
    var raw;
    try {
      raw = CALLS[name].apply(null, args);
    } catch (e) {
      throw new Error("Сбой моста: " + (e && e.message ? e.message : e));
    }
    var res;
    try {
      res = JSON.parse(raw);
    } catch (e) {
      throw new Error("Некорректный ответ движка");
    }
    if (!res.ok) {
      var err = new Error((res.error && res.error.message) || "Неизвестная ошибка");
      err.kind = res.error && res.error.kind;
      throw err;
    }
    return res.data;
  }

  function nativeJson(name) {
    if (!window.Native) return {};
    try { return JSON.parse(CALLS[name]()); } catch (e) { return {}; }
  }

  // ── screens ──────────────────────────────────────────────────────────
  function show(id) {
    hideToast();
    $$(".screen").forEach(function (s) { s.classList.toggle("active", s.id === id); });
    if (id !== "screen-settings") {
      applyTextZoomNow(false);
    }
    syncNativeComposer();
  }

  function fatal(message) {
    show("screen-boot");
    $(".boot-text").textContent = message;
    $(".boot-mark").style.animation = "none";
  }

  // ── formatting ───────────────────────────────────────────────────────
  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function renderMarkdown(text) {
    if (!text) return "";
    var parts = String(text).split("```");
    var html = "";
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (i % 2 === 1) {
        var nl = part.indexOf("\n");
        var lang = nl >= 0 ? part.slice(0, nl).trim().slice(0, 24) : "";
        var code = nl >= 0 ? part.slice(nl + 1) : part;
        var codeLang = normalizeCodeLang(lang);
        var canRun = /^(html|htm)$/i.test(lang);
        html += '<div class="code-block" data-lang="' + escapeHtml(lang) + '">' +
          '<div class="code-head" role="button" tabindex="0" aria-expanded="true">' +
          '<span class="code-meta"><span class="code-lang">' + escapeHtml(lang || "code") +
          '</span><span class="code-count"></span></span>' +
          '<span class="code-tools">' +
          (canRun
            ? '<button type="button" class="code-run">' +
              '<svg class="icon"><use href="#i-play"></use></svg><span>Запустить</span></button>'
            : "") +
          '<button type="button" class="code-copy">' +
          '<svg class="icon"><use href="#i-copy"></use></svg><span>Копировать</span></button>' +
          '<svg class="icon code-chevron"><use href="#i-expand"></use></svg></span></div>' +
          '<div class="code-body"><div class="code-inner"><div class="code-scroll">' +
          '<div class="code-gutter" aria-hidden="true"></div>' +
          '<pre class="code"><code class="' + (codeLang ? "language-" + codeLang : "") + '">' +
          escapeHtml(code.replace(/\n$/, "")) +
          "</code></pre></div></div></div></div>";
      } else {
        html += renderBlocks(part);
      }
    }
    return html;
  }

  function normalizeCodeLang(lang) {
    var value = String(lang || "").toLowerCase();
    var aliases = {
      js: "javascript", jsx: "javascript", ts: "typescript", tsx: "typescript",
      py: "python", rb: "ruby", rs: "rust", sh: "bash", shell: "bash", zsh: "bash",
      yml: "yaml", html: "xml", htm: "xml", cxx: "cpp", hpp: "cpp", cs: "csharp",
      kt: "kotlin", md: "markdown", text: "plaintext", plain: "plaintext"
    };
    return aliases[value] || value;
  }

  function lineCountText(count) {
    var mod10 = count % 10;
    var mod100 = count % 100;
    if (mod10 === 1 && mod100 !== 11) return count + " строка";
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) {
      return count + " строки";
    }
    return count + " строк";
  }

  // Highlight + line numbers are applied after the markdown HTML is mounted:
  // highlight.js owns the code spans and the gutter is generated from the raw
  // text, so copy/run still see clean source.
  function enhanceCodeBlocks(root) {
    if (!root) return;
    $$(".code-block", root).forEach(function (block) {
      var code = block.querySelector("pre code");
      if (!code) return;
      var raw = code.textContent || "";
      var lang = normalizeCodeLang(block.getAttribute("data-lang"));
      var hash = 0;
      for (var i = 0; i < raw.length; i++) {
        hash = (hash * 31 + raw.charCodeAt(i)) | 0;
      }
      var key = raw.length + ":" + hash + ":" + lang;
      if (block.getAttribute("data-enhanced") === key) return;
      block.setAttribute("data-enhanced", key);

      var lineTotal = raw.split("\n").length;
      var gutter = block.querySelector(".code-gutter");
      if (gutter) {
        var numbers = [];
        for (var line = 1; line <= lineTotal; line++) numbers.push(line);
        gutter.textContent = numbers.join("\n");
      }
      var count = block.querySelector(".code-count");
      if (count) count.textContent = lineCountText(lineTotal);

      if (window.hljs && lang && lang !== "plaintext" && lang !== "code" &&
          hljs.getLanguage(lang)) {
        code.className = "language-" + lang;
        try {
          hljs.highlightElement(code);
        } catch (e) {
          code.textContent = raw;
        }
      } else if (window.hljs) {
        code.className = "";
        code.textContent = raw;
      }
    });
  }

  function renderBlocks(text) {
    if (!text) return "";
    var lines = text.replace(/\r\n?/g, "\n").split("\n");
    var out = [];
    var para = [];
    var list = null;

    function flushPara() {
      if (!para.length) return;
      var body = inline(para.join("\n"));
      if (body.trim()) out.push("<p>" + body + "</p>");
      para = [];
    }

    function closeList() {
      if (list) {
        out.push("</" + list + ">");
        list = null;
      }
    }

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (!line.trim()) {
        flushPara();
        closeList();
        continue;
      }
      var m;
      if ((m = line.match(/^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/))) {
        flushPara();
        closeList();
        var level = m[1].length;
        out.push("<h" + level + ">" + inline(m[2]) + "</h" + level + ">");
        continue;
      }
      if (/^\s{0,3}([-*_])(?:\s*\1){2,}\s*$/.test(line)) {
        flushPara();
        closeList();
        out.push("<hr>");
        continue;
      }
      if ((m = line.match(/^\s{0,3}>\s?(.*)$/))) {
        flushPara();
        closeList();
        out.push("<blockquote>" + inline(m[1]) + "</blockquote>");
        continue;
      }
      if ((m = line.match(/^\s{0,3}[-*+]\s+(.*)$/))) {
        flushPara();
        if (list !== "ul") {
          closeList();
          out.push("<ul>");
          list = "ul";
        }
        out.push("<li>" + inline(m[1]) + "</li>");
        continue;
      }
      if ((m = line.match(/^\s{0,3}\d+[.)]\s+(.*)$/))) {
        flushPara();
        if (list !== "ol") {
          closeList();
          out.push("<ol>");
          list = "ol";
        }
        out.push("<li>" + inline(m[1]) + "</li>");
        continue;
      }
      if (list) closeList();
      para.push(line);
    }
    flushPara();
    closeList();
    return out.join("");
  }

  function inline(text) {
    var codes = [];
    var s = escapeHtml(text);
    s = s.replace(/`([^`\n]+)`/g, function (_, code) {
      codes.push("<code>" + code + "</code>");
      return "\u0000" + (codes.length - 1) + "\u0000";
    });
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, function (_, label, url) {
      return '<a href="' + url + '" rel="noopener">' + label + "</a>";
    });
    s = s.replace(/(^|[\s(])(https?:\/\/[^\s<)\].,;]+)/g, function (_, prefix, url) {
      return prefix + '<a href="' + url + '" rel="noopener">' + url + "</a>";
    });
    s = s.replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>");
    s = s.replace(/__([^_\n]+)__/g, "<strong>$1</strong>");
    s = s.replace(/~~([^~\n]+)~~/g, "<del>$1</del>");
    s = s.replace(/(^|[\s(])\*([^*\n]+)\*(?=[\s).,!?:;]|$)/g, "$1<em>$2</em>");
    s = s.replace(/\u0000(\d+)\u0000/g, function (_, index) {
      return codes[+index] || "";
    });
    return s;
  }

  function timeAgo(ts) {
    var diff = Math.max(0, Date.now() - Number(ts || 0));
    var min = Math.floor(diff / 60000);
    if (min < 1) return "только что";
    if (min < 60) return min + " мин назад";
    var h = Math.floor(min / 60);
    if (h < 24) return h + " ч назад";
    var d = Math.floor(h / 24);
    if (d === 1) return "вчера";
    if (d < 7) return d + " дн назад";
    try {
      return new Date(Number(ts)).toLocaleDateString("ru-RU", { day: "numeric", month: "short" });
    } catch (e) {
      return "";
    }
  }

  function hideToast() {
    var el = $("#toast");
    if (el) {
      el.classList.add("hidden");
      clearTimeout(toast._t);
    }
  }

  function toast(text) {
    var el = $("#toast");
    el.textContent = text;
    el.classList.remove("hidden");
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { el.classList.add("hidden"); }, 1700);
  }

  // ── onboarding & permissions ─────────────────────────────────────────
  function refreshPermissions() {
    var p = nativeJson("permissions");
    var nRow = $("#perm-notifications");
    var bRow = $("#perm-battery");
    var nBtn = $('button[data-perm="notifications"]', nRow);
    var bBtn = $('button[data-perm="battery"]', bRow);

    var nOk = p.notifications === "granted";
    nRow.classList.toggle("granted", nOk);
    nBtn.disabled = nOk;
    nBtn.classList.toggle("done", nOk);
    nBtn.textContent = nOk ? "Разрешено" : "Разрешить";

    var bOk = p.battery === "ignoring";
    bRow.classList.toggle("granted", bOk);
    bBtn.disabled = bOk;
    bBtn.classList.toggle("done", bOk);
    bBtn.textContent = bOk ? "Отключено" : "Отключить";
  }

  function wirePermissions() {
    $$("[data-perm]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var kind = btn.getAttribute("data-perm");
        if (kind === "notifications") {
          nativeJson("requestNotifications");
        } else {
          nativeJson("requestBattery");
        }
        setTimeout(refreshPermissions, 400);
      });
    });
  }

  // ── settings sheet & drawer ──────────────────────────────────────────
  var drawerSyncTimer = null;

  function openDrawer() {
    hideToast();
    refreshChats();
    // Drop the keyboard before the drawer starts sliding: otherwise the
    // viewport collapses mid-transition and the menu jumps.
    if (document.activeElement && document.activeElement.blur) {
      document.activeElement.blur();
    }
    if (window.Native && Native.hideKeyboard) {
      try { Native.hideKeyboard(); } catch (e) { /* old bridge */ }
    }
    if (window.Native && Native.hideComposer) {
      try { Native.hideComposer(); } catch (e) { /* old bridge */ }
    }
    if (drawerSyncTimer) {
      clearTimeout(drawerSyncTimer);
      drawerSyncTimer = null;
    }
    $("#drawer").classList.add("open");
    $("#drawer-backdrop").classList.remove("hidden");
    // Hide the native bar immediately (and drop the keyboard), but give the
    // drawer the full slide-in to cover the WebView.
    syncNativeComposer();
  }

  function closeDrawer() {
    $("#drawer").classList.remove("open");
    $("#drawer-backdrop").classList.add("hidden");
    // The drawer slides out for 320 ms. Re-showing the native bar in the same
    // frame would paint it over the still visible menu.
    clearTimeout(drawerSyncTimer);
    drawerSyncTimer = setTimeout(function () {
      drawerSyncTimer = null;
      syncNativeComposer();
    }, 360);
  }

  function currentScreen() {
    var el = document.querySelector(".screen.active");
    return el ? el.id : "screen-chat";
  }

  function fillAccess() {
    var webMode = isWebMode();
    $("#sheet-mode").textContent = webMode
      ? "веб-сессия chat.deepseek.com"
      : "официальный API";
    $("#sheet-cred-label").textContent = webMode ? "Токен" : "Ключ";
    $("#sheet-note").textContent = webMode
      ? "Неофициальный протокол. DeepSeek может отозвать токен или заблокировать аккаунт."
      : "Ключ с platform.deepseek.com. Хранится в приватной папке приложения.";

    var keyText = S.status && S.status.has_token ? "сохранён" : "не добавлен";
    if (S.status && S.status.balance) {
      keyText += " · " + balanceText(S.status.balance);
    }
    $("#sheet-token").textContent = keyText;
    $("#sheet-version").textContent = S.version ? "v" + S.version : "";
  }

  var SETTINGS_PANES = {
    model: "Модель и ИИ",
    token: "Авторизация и ключи",
    server: "Локальный API",
    tunnel: "Cloudflare Туннель",
    system: "О приложении",
    ui: "Интерфейс"
  };

  function paintSettingsCatalog() {
    var status = S.status || {};
    var mode = isWebMode() ? "Web" : "API";
    $("#cat-sub-model").textContent = mode + " · " + modelShortName();
    $("#cat-sub-token").textContent = !status.has_token
      ? "Токен не добавлен"
      : (isWebMode() ? "Веб-токен сохранён" : "Ключ API сохранён");
    $("#cat-sub-server").textContent = S.server && S.server.running
      ? "127.0.0.1:" + S.server.port
      : "Выключен";
    var cfOn = $("#cf-toggle") && $("#cf-toggle").checked;
    $("#cat-sub-tunnel").textContent = cfOn
      ? (($("#cf-status") && $("#cf-status").textContent) || "Включён")
      : "Выключен";
    var version = S.version ? "v" + S.version : "версия неизвестна";
    var available = S.update && S.update.versionName
      ? " · доступно " + S.update.versionName
      : "";
    $("#cat-sub-system").textContent = version + available;
    var theme = S.theme === "light" ? "Светлая"
      : (S.theme === "dark" ? "Тёмная" : "Системная");
    $("#cat-sub-ui").textContent = theme + " · текст " + S.textZoom + "%";
  }

  function showSettingsRoot() {
    S.settingsPane = null;
    $("#settings-title").textContent = "Настройки";
    $("#settings-root").classList.remove("hidden");
    $("#settings-detail").classList.add("hidden");
    $$(".settings-pane").forEach(function (pane) {
      pane.classList.remove("active");
    });
    var scroll = $(".settings-scroll");
    if (scroll) scroll.scrollTop = 0;
    paintSettingsCatalog();
  }

  function openSettingsPane(name) {
    if (!SETTINGS_PANES[name]) return;
    S.settingsPane = name;
    $("#settings-title").textContent = SETTINGS_PANES[name];
    $("#settings-root").classList.add("hidden");
    $("#settings-detail").classList.remove("hidden");
    $$(".settings-pane").forEach(function (pane) {
      pane.classList.toggle(
        "active",
        pane.getAttribute("data-settings-pane") === name
      );
    });
    var scroll = $(".settings-scroll");
    if (scroll) scroll.scrollTop = 0;
    paintSettingsCatalog();
  }

  function closeSettingsPane() {
    showSettingsRoot();
  }

  function openSettings() {
    hideToast();
    closeDrawer();
    var current = currentScreen();
    if (current !== "screen-settings") {
      S.settingsReturn = current;
    }
    show("screen-settings");
    showSettingsRoot();
    paintModel();
    paintTheme();
    paintTextZoom();
    applyTextZoomNow(true);
    paintCompact();
    paintOnline();
    refreshServerStatus();
    ensureLocalService();
    paintCfMode();
    refreshTunnelStatus();
    fillAccess();
    paintSettingsTokenMode();
    refreshSettingsTokenButton();
    paintSettingsCatalog();
  }

  function closeSettings() {
    S.settingsPane = null;
    show(S.settingsReturn || "screen-chat");
  }

  function paintSettingsTokenMode() {
    $$("#settings-auth-mode button").forEach(function (button) {
      button.classList.toggle(
        "active",
        button.getAttribute("data-settings-auth") === S.authMode
      );
    });
    var input = $("#settings-token-input");
    if (input) {
      input.placeholder = S.authMode === "web" ? '{"value":"…"}' : "sk-…";
    }
  }

  function refreshSettingsTokenButton() {
    var input = $("#settings-token-input");
    var button = $("#settings-token-save");
    if (!input || !button) return;
    button.disabled = !S.online || input.value.trim().length < 8;
  }

  function saveSettingsToken() {
    var input = $("#settings-token-input");
    var note = $("#settings-token-note");
    var button = $("#settings-token-save");
    if (!input || !note || !button) return;
    var value = input.value.trim();
    if (value.length < 8 || !S.online) return;
    button.disabled = true;
    button.textContent = "Проверяю…";
    note.classList.remove("token-error");
    note.textContent = "Проверяю токен…";
    setTimeout(function () {
      try {
        S.status = invoke("setToken", value, S.authMode);
        S.online = S.status.online !== false;
        input.value = "";
        applyAuthMode(S.status.mode, false);
        paintSettingsTokenMode();
        fillAccess();
        paintModel();
        paintOnline();
        paintSettingsCatalog();
        note.textContent = "Токен сохранён.";
        toast("Токен сохранён");
      } catch (e) {
        note.textContent = tokenErrorText(e);
        note.classList.add("token-error");
      } finally {
        button.textContent = "Сохранить";
        refreshSettingsTokenButton();
      }
    }, 80);
  }

  var dialogResolve = null;

  function confirmDialog(title, text, okLabel) {
    return new Promise(function (resolve) {
      dialogResolve = resolve;
      $("#dialog-title").textContent = title;
      $("#dialog-text").textContent = text;
      $("#dialog-ok").textContent = okLabel || "OK";
      $("#dialog-scrim").classList.remove("hidden");
      syncNativeComposer();
    });
  }

  function closeDialog(result) {
    $("#dialog-scrim").classList.add("hidden");
    syncNativeComposer();
    var resolve = dialogResolve;
    dialogResolve = null;
    if (resolve) resolve(!!result);
  }

  // ── app updates ──────────────────────────────────────────────────────
  var SOURCE_URL = "https://github.com/Rewixx-png/localapi-deepseek";
  var updaterStarted = false;

  function updateRemindEnabled() {
    return localStorage.getItem("updateRemind") !== "0";
  }

  function syncUpdatePrefs() {
    if (!window.Native || !Native.setUpdatePrefs) return;
    try { Native.setUpdatePrefs(!!S.online, updateRemindEnabled()); } catch (e) { /* old bridge */ }
  }

  function setUpdateRemind(on) {
    localStorage.setItem("updateRemind", on ? "1" : "0");
    var toggle = $("#update-remind-toggle");
    if (toggle) toggle.checked = !!on;
    syncUpdatePrefs();
  }

  function updateFromRaw(raw) {
    if (!raw) return null;
    try {
      var update = JSON.parse(raw);
      return update && update.url ? update : null;
    } catch (e) {
      return null;
    }
  }

  function paintUpdateNote(text) {
    var note = $("#update-note");
    if (note) note.textContent = text;
  }

  function resetCheckButton() {
    var btn = $("#check-update");
    if (!btn) return;
    btn.disabled = false;
    btn.textContent = "Проверить";
  }

  function setUpdateProgress(percent) {
    var wrap = $("#update-progress");
    var bar = $("#update-bar");
    var label = $("#update-percent");
    if (!wrap || !bar || !label) return;
    if (percent == null || percent < 0) {
      wrap.classList.add("hidden");
      bar.style.width = "0%";
      label.textContent = "";
      return;
    }
    var value = Math.max(0, Math.min(100, parseInt(percent, 10) || 0));
    wrap.classList.remove("hidden");
    bar.style.width = value + "%";
    label.textContent = value + "%";
  }

  function showUpdateDialog(update, manual) {
    if (!update) return;
    if (!manual && !updateRemindEnabled()) return;
    if (S.updateBusy) return;
    var currentCode = S.update && S.update.versionCode
      ? S.update.versionCode
      : 0;
    if (update.versionCode && currentCode && update.versionCode < currentCode) {
      return;
    }
    var visible = !$("#update-scrim").classList.contains("hidden");
    S.update = update;
    $("#update-version").textContent = "Версия " + (update.versionName || "");
    $("#update-notes").textContent = update.notes || "Доступна новая версия приложения.";
    if (visible) {
      // A stored update is shown immediately on launch; the fresh check may
      // discover a newer one while the dialog is already open. Refresh its
      // content instead of keeping the stale version on screen.
      return;
    }
    $("#update-error").textContent = "";
    $("#update-error").classList.add("hidden");
    setUpdateProgress(-1);
    $("#update-now").disabled = false;
    $("#update-later").disabled = false;
    $("#update-never").disabled = false;
    $("#update-later").classList.remove("hidden");
    $("#update-never").classList.remove("hidden");
    $("#update-scrim").classList.remove("hidden");
    syncNativeComposer();
  }

  function closeUpdateDialog() {
    if (S.updateBusy) return;
    $("#update-scrim").classList.add("hidden");
    syncNativeComposer();
  }

  function downloadUpdate() {
    S.updateBusy = true;
    $("#update-error").classList.add("hidden");
    $("#update-now").disabled = true;
    $("#update-later").disabled = true;
    $("#update-never").disabled = true;
    setUpdateProgress(0);
    try {
      Native.downloadUpdate();
    } catch (e) {
      window.__updateError("Мост приложения недоступен");
    }
  }

  function initUpdater() {
    if (updaterStarted || !NATIVE_COMPOSER || !window.Native) return;
    updaterStarted = true;
    syncUpdatePrefs();
    var pending = updateFromRaw(
      Native.pendingUpdate ? Native.pendingUpdate() : ""
    );
    if (pending && updateRemindEnabled() && S.status && S.status.has_token) {
      showUpdateDialog(pending, false);
    }
    if (!S.online) return;
    try {
      if (Native.checkUpdate) Native.checkUpdate(false);
    } catch (e) { /* old bridge */ }
  }

  window.__updateAvailable = function (raw, manual) {
    resetCheckButton();
    var update = updateFromRaw(raw);
    if (!update) return;
    paintUpdateNote("Доступно " + (update.versionName || "обновление"));
    if (manual || updateRemindEnabled()) showUpdateDialog(update, manual);
  };

  window.__updateNone = function (manual) {
    resetCheckButton();
    paintUpdateNote("У вас последняя версия");
    if (manual) {
      toast("У вас последняя версия" + (S.version ? " — v" + S.version : ""));
    }
  };

  window.__updateError = function (message) {
    resetCheckButton();
    paintUpdateNote("Не удалось проверить обновления");
    if ($("#update-scrim").classList.contains("hidden")) {
      toast(message || "Не удалось проверить обновления");
      return;
    }
    S.updateBusy = false;
    $("#update-error").textContent = message || "Не удалось обновить приложение";
    $("#update-error").classList.remove("hidden");
    $("#update-now").disabled = false;
    $("#update-later").disabled = false;
    $("#update-never").disabled = false;
  };

  window.__updateProgress = function (percent) {
    setUpdateProgress(percent);
  };

  window.__updateStatus = function (state) {
    if (state === "permission") {
      S.updateBusy = false;
      $("#update-error").textContent =
        "Разрешите установку из неизвестных источников, затем вернитесь в приложение.";
      $("#update-error").classList.remove("hidden");
      $("#update-now").disabled = false;
      $("#update-later").disabled = false;
      $("#update-never").disabled = false;
      setUpdateProgress(-1);
    } else if (state === "installing") {
      S.updateBusy = false;
      setUpdateProgress(100);
      $("#update-percent").textContent = "100% · Подтвердите установку";
      $("#update-error").classList.add("hidden");
      $("#update-now").disabled = true;
      $("#update-later").disabled = false;
      $("#update-never").disabled = true;
    } else if (state === "installed") {
      S.updateBusy = false;
      toast("Обновление установлено");
      closeUpdateDialog();
    }
  };

  window.__showPendingUpdate = function () {
    var pending = updateFromRaw(
      Native.pendingUpdate ? Native.pendingUpdate() : ""
    );
    if (pending) showUpdateDialog(pending, true);
  };

  function wireUpdater() {
    var toggle = $("#update-remind-toggle");
    if (toggle) {
      toggle.checked = updateRemindEnabled();
      toggle.addEventListener("change", function () {
        setUpdateRemind(this.checked);
      });
    }
    $("#update-now").addEventListener("click", downloadUpdate);
    $("#update-later").addEventListener("click", closeUpdateDialog);
    $("#update-never").addEventListener("click", function () {
      setUpdateRemind(false);
      S.updateBusy = false;
      closeUpdateDialog();
      toast("Больше не напоминаем об обновлениях");
    });
    $("#update-scrim").addEventListener("click", function (e) {
      if (e.target === this) closeUpdateDialog();
    });
    $("#check-update").addEventListener("click", function () {
      if (!S.online) {
        toast("Нет доступа к интернету");
        return;
      }
      var btn = this;
      btn.disabled = true;
      btn.textContent = "Проверяю…";
      try {
        Native.checkUpdate(true);
      } catch (e) {
        resetCheckButton();
        toast("Мост приложения недоступен");
      }
    });
    $("#open-source").addEventListener("click", function () {
      if (window.Native && Native.openUrl) {
        try {
          Native.openUrl(SOURCE_URL);
        } catch (e) {
          toast("Не удалось открыть ссылку");
        }
      } else {
        window.open(SOURCE_URL, "_blank");
      }
    });
  }

  function balanceText(b) {
    var symbol = b.currency === "USD" ? "$" : b.currency === "CNY" ? "¥" : "";
    return symbol + b.total + " " + (b.currency === "USD" ? "" : b.currency);
  }

  function isWebMode() {
    return !!(S.status && S.status.has_token
      ? S.status.mode === "web"
      : S.authMode === "web");
  }

  function modelShortName() {
    if (isWebMode()) return "DeepSeek";
    return S.model === "deepseek-v4-pro" ? "V4 Pro" : "Flash 1017";
  }

  function paintModel() {
    var web = isWebMode();
    var flash = web ? "Стандарт" : "Flash 1017";
    var pro = web ? "Эксперт" : "V4 Pro";

    $$("#model-select button").forEach(function (b) {
      var isPro = b.getAttribute("data-model") === "deepseek-v4-pro";
      b.textContent = isPro ? pro : flash;
      b.classList.toggle("active", b.getAttribute("data-model") === S.model);
    });
    $("#reasoning-toggle").checked = !!S.thinking;
    $("#reasoning-note").textContent = S.thinking ? "DeepThink" : "Выключены";

    var showEffort = S.thinking && !web;
    $("#model-block").classList.toggle("hidden", web);
    $("#sheet-model-row").classList.toggle("hidden", web);
    $("#effort-setting").classList.toggle("hidden", !showEffort);
    $("#effort-divider").classList.toggle("hidden", !showEffort);
    $("#reasoning-chip").classList.toggle("active", !!S.thinking);
    $("#search-chip").classList.toggle("active", !!S.search);
    $("#search-toggle").checked = !!S.search;
    $("#search-note").textContent = S.search
      ? (web ? "Включён · веб-поиск" : "Включён · DeepSeek Search")
      : (web ? "Веб-поиск в чате" : "DeepSeek Search · платно за запрос");
    $$("[data-effort]").forEach(function (b) {
      b.classList.toggle("active", b.getAttribute("data-effort") === S.effort);
    });
    $("#sheet-model").textContent =
      (S.model === "deepseek-v4-pro" ? pro : flash) + (S.thinking ? " · reasoning" : "");

    var empty = $("#messages .empty");
    if (empty && (!S.chat || !S.chat.messages || !S.chat.messages.length)) {
      renderMessages();
    }
  }

  function paintTheme() {
    var light = S.theme === "light";
    if (S.theme === "system") {
      light = !!(window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches);
    }
    document.documentElement.setAttribute("data-theme", light ? "light" : "dark");
    try {
      if (window.Native && window.Native.setBars) window.Native.setBars(light);
      if (NATIVE_COMPOSER) Native.setComposerTheme(light);
    } catch (e) { /* bridge unavailable in tests */ }
    $$("#theme-select button").forEach(function (b) {
      b.classList.toggle("active", b.getAttribute("data-theme-choice") === S.theme);
    });
  }

  function updateSliderFill(slider, value) {
    var fill = ((value - 85) / (150 - 85)) * 100;
    slider.style.setProperty("--fill", fill + "%");
  }

  function applyTextZoomNow(force) {
    if (!force && S.appliedZoom === S.textZoom) return;
    S.appliedZoom = S.textZoom;
    var preview = $(".chat-preview");
    if (preview) preview.style.setProperty("--preview-scale", "1");
    try {
      if (window.Native && window.Native.setTextZoom) {
        window.Native.setTextZoom(S.textZoom);
      }
    } catch (e) { /* bridge unavailable in tests */ }
  }

  function paintTextZoom() {
    var slider = $("#text-slider");
    if (slider) {
      slider.value = S.textZoom;
      updateSliderFill(slider, S.textZoom);
    }
    var label = $("#text-value");
    if (label) label.textContent = S.textZoom + "%";
  }

  function paintCompact() {
    document.body.classList.toggle("compact", !!S.compact);
    $("#compact-toggle").checked = !!S.compact;
  }

  function focusComposer() {
    if (NATIVE_COMPOSER && window.Native.focusComposer) {
      Native.focusComposer();
      return;
    }
    var input = $("#input");
    if (input) input.focus();
  }

  function syncNativeComposer() {
    if (!NATIVE_COMPOSER) return;
    var chat = $("#screen-chat").classList.contains("active");
    var drawer = $("#drawer").classList.contains("open");
    var dialog = !$("#dialog-scrim").classList.contains("hidden");
    var viewer = !$("#image-viewer").classList.contains("hidden");
    var update = !$("#update-scrim").classList.contains("hidden");
    Native.setComposerVisible(chat && !drawer && !dialog && !viewer && !update);
  }

  function openImageViewer(src) {
    if (!src) return;
    $("#image-viewer-img").src = src;
    $("#image-viewer").classList.remove("hidden");
    syncNativeComposer();
  }

  function closeImageViewer() {
    $("#image-viewer").classList.add("hidden");
    $("#image-viewer-img").src = "";
    syncNativeComposer();
  }

  function syncComposerState() {
    if (!NATIVE_COMPOSER) return;
    Native.setComposerState(!!S.stream, !!S.online);
  }

  function paintOnline() {
    var online = !!S.online;
    $("#online-toggle").checked = online;
    $("#online-note").textContent = online ? "Запросы разрешены" : "Запросы заблокированы";
    $("#offline-banner").classList.toggle("hidden", online);
    $("#token-offline").classList.toggle("hidden", online);
    $("#send-btn").disabled = !online;
    $("#server-toggle").disabled = !online;
    $("#cf-toggle").disabled = !online;
    $("#sheet-revalidate").disabled = !online;
    syncComposerState();
    syncUpdatePrefs();
    refreshTokenButton();
    refreshSettingsTokenButton();
  }

  function ensureLocalService() {
    if (!S.server || !S.server.running || !S.server.port) return;
    try { invoke("ensureService", S.server.port); } catch (e) { /* old bridge */ }
  }

  // The Rust server dies with the process, so reopening the app must restore
  // the user's "server enabled" setting and start ServerService again.
  function maybeStartLocalServer() {
    if (localStorage.getItem("serverEnabled") !== "1") return;
    if (!S.status || !S.status.has_token || !S.online) return;
    try {
      S.server = invoke("startServer", 8787);
    } catch (e) {
      toast("Локальный API: " + e.message);
    }
    paintServer();
  }

  function paintServer() {
    var web = isWebMode();
    var toggle = $("#server-toggle");
    toggle.checked = !!S.server.running;
    toggle.disabled = !S.online;
    $("#server-web-note").classList.toggle("hidden", !(web && S.server.running));
    $("#server-info").classList.toggle("hidden", !S.server.running);
    $("#server-note").textContent = S.server.running
      ? "127.0.0.1:" + S.server.port
      : "127.0.0.1:8787";
    $("#server-model").textContent = isWebMode() ? "deepseek-flash" : S.model;
    if (S.server.running) {
      $("#server-url").textContent = S.server.base_url;
      $("#server-key").textContent = S.server.key;
    }
  }

  function refreshServerStatus() {
    if (!S.status || !S.status.has_token || !S.online) {
      S.server = { running: false, port: 0, key: "", base_url: "" };
    } else {
      try {
        S.server = invoke("serverStatus");
      } catch (e) {
        S.server = { running: false, port: 0, key: "", base_url: "" };
      }
    }
    paintServer();
  }

  function paintCfMode() {
    $$("#cf-mode button").forEach(function (button) {
      button.classList.toggle("active", button.getAttribute("data-cf-mode") === S.cfMode);
    });
    var token = S.cfMode === "token";
    $("#cf-token-field").classList.toggle("hidden", !token);
    $("#cf-host-field").classList.toggle("hidden", !token);
  }

  function paintTunnel(status) {
    var st = status || { state: "stopped", mode: S.cfMode, url: "", error: "" };
    var active = st.state === "starting" || st.state === "connected";
    $("#cf-toggle").checked = active;
    var text = "Выключен";
    if (st.state === "starting") text = st.url ? "Подключаюсь…" : "Запускаю…";
    else if (st.state === "connected") text = "Туннель работает";
    else if (st.state === "error") text = "Ошибка";
    if (st.state === "error" && st.error) text += ": " + st.error;
    $("#cf-status").classList.toggle("error", st.state === "error");
    $("#cf-note").textContent = active
      ? "Публичный HTTPS включён"
      : "Публичный HTTPS для локального API";
    var detail = st.detail ? String(st.detail) : "";
    if (st.state === "connected" && /dial tcp|connection refused|bad gateway|unreachable|timeout|502/i.test(detail)) {
      text = "Туннель есть, но локальный API не отвечает";
      $("#cf-status").classList.add("error");
      $("#cf-note").textContent = "Cloudflare не доходит до локального API";
    }
    $("#cf-status").textContent = text;
    var url = st.url || "";
    $("#cf-url-row").classList.toggle("hidden", !url);
    $("#cf-url").textContent = url || "—";
    $("#cf-copy").classList.toggle("hidden", !url);
  }

  function refreshTunnelStatus() {
    try {
      if (!window.Native || !Native.tunnelStatus) return;
      var status = invoke("tunnelStatus");
      paintTunnel(status);
    } catch (e) {
      paintTunnel({ state: "stopped" });
    }
  }

  function stopCfTunnel() {
    try { invoke("stopTunnel"); } catch (e) { /* old bridge */ }
    paintTunnel({ state: "stopped" });
  }

  function startCfTunnel() {
    if (!S.server || !S.server.running) {
      paintTunnel({ state: "stopped" });
      toast("Сначала запусти локальный API");
      return;
    }
    var token = $("#cf-token").value.trim();
    var host = $("#cf-host").value.trim();
    if (S.cfMode === "token" && !token) {
      paintTunnel({ state: "stopped" });
      toast("Вставь токен туннеля");
      return;
    }
    if (S.cfMode === "token" && !host) {
      paintTunnel({ state: "stopped" });
      toast("Укажи публичный адрес из Cloudflare");
      return;
    }
    confirmDialog(
      "Cloudflare Tunnel",
      "Локальный API станет доступен из интернета по HTTPS. Любой, у кого есть ключ, " +
      "сможет расходовать твой токен DeepSeek. Для постоянного адреса используй " +
      "именованный туннель и Cloudflare Access.",
      "Включить"
    ).then(function (ok) {
      if (!ok) {
        paintTunnel({ state: "stopped" });
        return;
      }
      localStorage.setItem("cfHost", host);
      try {
        var status = invoke("startTunnel", S.cfMode, token, host, S.server.port);
        paintTunnel(status);
        if (status.state === "error") {
          toast(status.error || "Туннель не запустился");
        }
      } catch (e) {
        paintTunnel({ state: "stopped" });
        toast(e.message);
      }
    });
  }

  function wireSettings() {
    $("#offline-enable").addEventListener("click", function () {
      try {
        S.status = invoke("setOnline", true);
        S.online = !!S.status.online;
      } catch (e) {
        toast(e.message);
      }
      paintOnline();
      paintServer();
    });

    $$("#model-select button").forEach(function (b) {
      b.addEventListener("click", function () {
        S.model = b.getAttribute("data-model");
        localStorage.setItem("model", S.model);
        paintModel();
      });
    });

    $("#reasoning-toggle").addEventListener("change", function () {
      S.thinking = this.checked;
      localStorage.setItem("thinking", S.thinking ? "1" : "0");
      paintModel();
    });

    $$("[data-effort]").forEach(function (b) {
      b.addEventListener("click", function () {
        S.effort = b.getAttribute("data-effort");
        localStorage.setItem("effort", S.effort);
        paintModel();
      });
    });

    $$("#theme-select button").forEach(function (b) {
      b.addEventListener("click", function () {
        S.theme = b.getAttribute("data-theme-choice");
        localStorage.setItem("theme", S.theme);
        paintTheme();
      });
    });

    $("#text-slider").addEventListener("input", function () {
      var value = Math.max(85, Math.min(150, parseInt(this.value, 10) || 100));
      S.textZoom = value;
      localStorage.setItem("textZoom", String(value));
      var label = $("#text-value");
      if (label) label.textContent = value + "%";
      updateSliderFill(this, value);
      var preview = $(".chat-preview");
      if (preview) {
        var base = S.appliedZoom || 100;
        preview.style.setProperty("--preview-scale", String(value / base));
      }
    });

    $("#compact-toggle").addEventListener("change", function () {
      S.compact = this.checked;
      localStorage.setItem("compact", S.compact ? "1" : "0");
      paintCompact();
    });

    $("#server-toggle").addEventListener("change", function () {
      var on = this.checked;
      try {
        if (on) {
          S.server = invoke("startServer", 8787);
          localStorage.setItem("serverEnabled", "1");
        } else {
          stopCfTunnel();
          invoke("stopServer");
          localStorage.setItem("serverEnabled", "0");
          S.server = { running: false, port: 0, key: "", base_url: "" };
        }
      } catch (e) {
        this.checked = !on;
        toast(e.message);
      }
      paintServer();
    });

    $("#cf-mode").addEventListener("click", function (event) {
      var button = event.target.closest("button[data-cf-mode]");
      if (!button) return;
      var mode = button.getAttribute("data-cf-mode");
      if (mode === S.cfMode) return;
      if ($("#cf-toggle").checked) {
        stopCfTunnel();
      }
      S.cfMode = mode;
      localStorage.setItem("cfMode", mode);
      paintCfMode();
      paintTunnel({ state: "stopped" });
    });

    $("#cf-toggle").addEventListener("change", function () {
      if (this.checked) {
        startCfTunnel();
      } else {
        stopCfTunnel();
      }
    });

    $("#cf-host").addEventListener("change", function () {
      localStorage.setItem("cfHost", this.value.trim());
    });

    $("#cf-copy").addEventListener("click", function () {
      var url = $("#cf-url").textContent;
      if (url && url !== "—") copyText(url);
    });

    $("#server-copy-key").addEventListener("click", function () {
      if (S.server.key) copyText(S.server.key);
    });
    $("#server-copy-url").addEventListener("click", function () {
      if (S.server.base_url) copyText(S.server.base_url);
    });
    $("#server-copy-model").addEventListener("click", function () {
      copyText(S.model);
    });

    $("#online-toggle").addEventListener("change", function () {
      var on = this.checked;
      try {
        S.status = invoke("setOnline", on);
        S.online = !!S.status.online;
        if (!on) {
          detachStream();
          stopCfTunnel();
          if (S.server.running) {
            try { invoke("stopServer"); } catch (e) { /* ignore */ }
          }
          localStorage.setItem("serverEnabled", "0");
          S.server = { running: false, port: 0, key: "", base_url: "" };
        }
      } catch (e) {
        this.checked = !on;
        toast(e.message);
      }
      paintOnline();
      paintServer();
    });
  }

  // ── token setup ──────────────────────────────────────────────────────
  var TOKEN_HINTS = {
    api: "platform.deepseek.com → API keys → Create new key",
    web: "chat.deepseek.com → DevTools → Application → Local Storage → userToken"
  };

  function applyAuthMode(mode, reset) {
    S.authMode = mode === "web" ? "web" : "api";
    localStorage.setItem("authMode", S.authMode);
    var isWeb = S.authMode === "web";

    $$("#auth-mode button").forEach(function (b) {
      b.classList.toggle("active", b.getAttribute("data-mode") === S.authMode);
    });

    $("#token-label").textContent = isWeb ? "userToken" : "API-ключ";
    $("#token-hint").textContent = TOKEN_HINTS[S.authMode];
    $("#token-lead").textContent = isWeb
      ? "Вставьте userToken из chat.deepseek.com. Приложение проверит его запросом к /users/settings."
      : "Вставьте API-ключ. Приложение проверит его запросом к /models и сохранит на устройстве.";
    $("#token-input").placeholder = isWeb ? '{"value":"…"}' : "sk-…";
    $("#web-warning").classList.toggle("hidden", !isWeb);
    $("#token-fine").innerHTML = isWeb
      ? "DeepSeek может изменить протокол без предупреждения."
      : "Ключ хранится в приватной папке приложения, права 0600.";

    paintModel();

    if (reset) {
      var input = $("#token-input");
      input.value = "";
      input.type = "password";
      $("#token-eye").textContent = "показать";
      $("#token-result").classList.add("hidden");
      $("#token-error").classList.add("hidden");
      $("#token-save").textContent = "Проверить и сохранить";
      refreshTokenButton();
    }
  }

  function showTokenScreen() {
    show("screen-token");
    applyAuthMode(S.authMode, false);
    paintOnline();
    $("#token-result").classList.add("hidden");
    $("#token-error").classList.add("hidden");
    setTimeout(function () { $("#token-input").focus(); }, 250);
  }

  function refreshTokenButton() {
    var input = $("#token-input");
    $("#token-save").disabled = !S.online || input.value.trim().length < 8;
  }

  function wireTokenScreen() {
    var input = $("#token-input");
    var save = $("#token-save");
    var error = $("#token-error");

    $("#token-eye").addEventListener("click", function () {
      input.type = input.type === "password" ? "text" : "password";
      this.textContent = input.type === "password" ? "показать" : "скрыть";
    });

    $("#token-online-enable").addEventListener("click", function () {
      try {
        S.status = invoke("setOnline", true);
        S.online = !!S.status.online;
      } catch (e) {
        toast(e.message);
      }
      paintOnline();
      paintServer();
    });

    $("#token-paste").addEventListener("click", function () {
      var text = "";
      try { text = window.Native.readClipboard() || ""; } catch (e) { text = ""; }
      text = text.trim();
      if (!text) { toast("Буфер обмена пуст"); return; }
      input.value = text;
      input.type = "text";
      $("#token-eye").textContent = "скрыть";
      refreshTokenButton();
      input.focus();
    });

    input.addEventListener("input", function () {
      error.classList.add("hidden");
      refreshTokenButton();
    });
    save.disabled = true;

    input.addEventListener("keydown", function (e) {
      if (e.key === "Enter") { e.preventDefault(); save.click(); }
    });

    $$("#auth-mode button").forEach(function (b) {
      b.addEventListener("click", function () {
        applyAuthMode(b.getAttribute("data-mode"), true);
        $("#token-input").focus();
      });
    });

    save.addEventListener("click", function () {
      var token = input.value.trim();
      if (token.length < 8) return;
      save.disabled = true;
      save.textContent = "Проверяю…";
      error.classList.add("hidden");
      // Let the browser paint the spinner before the blocking JNI call.
      setTimeout(function () {
        try {
          var status = invoke("setToken", token, S.authMode);
          S.status = status;
          input.value = "";
          input.type = "password";
          applyAuthMode(status.mode, false);
          refreshServerStatus();
          showTokenResult(status);
        } catch (e) {
          error.textContent = tokenErrorText(e);
          error.classList.remove("hidden");
          save.disabled = false;
          save.textContent = "Проверить и сохранить";
        }
      }, 80);
    });

    $("#token-open").addEventListener("click", function () {
      enterChat();
    });
  }

  function tokenErrorText(e) {
    if (e.kind === "unauthorized") {
      return e.message + " Проверьте, что ключ скопирован целиком и проект активен.";
    }
    if (e.kind === "network") {
      return e.message + " Проверьте интернет и повторите.";
    }
    return e.message;
  }

  function showTokenResult(status) {
    $("#token-save").textContent = "Проверить и сохранить";
    $("#token-save").disabled = false;
    $("#token-result").classList.remove("hidden");
    $("#result-mode").textContent = status.mode === "web"
      ? "веб-сессия chat.deepseek.com"
      : "официальный API";
    var row = $("#result-balance-row");
    if (status.balance) {
      row.classList.remove("hidden");
      $("#result-balance").textContent = balanceText(status.balance);
    } else {
      row.classList.add("hidden");
    }
    $("#token-input").blur();
  }

  // ── chat rendering ───────────────────────────────────────────────────
  function enterChat() {
    show("screen-chat");
    if (!S.chat) {
      renderMessages();
    }
    refreshChats();
    scrollToBottom(true);
  }

  function toggleCodeBlock(block, head) {
    if (!block) return;
    var collapsed = block.classList.toggle("collapsed");
    if (head) head.setAttribute("aria-expanded", collapsed ? "false" : "true");
  }

  function sourcesEl(sources) {
    if (!sources || !sources.length) return null;
    var box = document.createElement("div");
    box.className = "sources";
    var title = document.createElement("div");
    title.className = "sources-title";
    title.textContent = "Источники";
    box.appendChild(title);
    sources.forEach(function (source) {
      var link = document.createElement("a");
      link.href = source.url;
      link.textContent = source.title || source.url;
      if (source.site || source.date) {
        var meta = document.createElement("span");
        meta.textContent = [source.site, source.date].filter(Boolean).join(" · ");
        link.appendChild(meta);
      }
      box.appendChild(link);
    });
    return box;
  }

  function reasoningEl(text, open) {
    var box = document.createElement("div");
    box.className = "reasoning" + (open ? " open" : "");
    var toggle = document.createElement("button");
    toggle.type = "button";
    toggle.className = "reasoning-toggle";
    toggle.setAttribute("aria-expanded", open ? "true" : "false");
    toggle.innerHTML = '<svg class="icon"><use href="#i-expand"></use></svg><span>Размышления</span>';
    var wrap = document.createElement("div");
    wrap.className = "reasoning-wrap";
    var body = document.createElement("div");
    body.className = "reasoning-body";
    var textEl = document.createElement("div");
    textEl.className = "reasoning-text";
    textEl.textContent = text || "";
    body.appendChild(textEl);
    wrap.appendChild(body);
    box.appendChild(toggle);
    box.appendChild(wrap);
    box._text = textEl;
    toggle.addEventListener("click", function () {
      var isOpen = box.classList.toggle("open");
      toggle.setAttribute("aria-expanded", isOpen ? "true" : "false");
    });
    return box;
  }

  function messageEl(m, opts) {
    opts = opts || {};
    var row = document.createElement("div");
    row.className = "msg " + (m.role === "user" ? "user" : "assistant");

    if (m.role !== "user") {
      var avatar = document.createElement("div");
      avatar.className = "avatar";
      avatar.innerHTML = '<svg class="brand-icon"><use href="#i-brand"></use></svg>';
      row.appendChild(avatar);
    }

    var bubble = document.createElement("div");
    bubble.className = "bubble";

    if (m.role === "user") {
      if (m.images && m.images.length) {
        var gallery = document.createElement("div");
        gallery.className = "msg-images" + (m.images.length === 1 ? " single" : "");
        m.images.forEach(function (src) {
          var image = document.createElement("img");
          image.className = "msg-image";
          image.src = src;
          gallery.appendChild(image);
        });
        bubble.appendChild(gallery);
      }
      if (m.content) {
        var content = document.createElement("div");
        content.className = "content";
        content.textContent = m.content;
        bubble.appendChild(content);
      }
    } else {
      if (m.reasoning) {
        bubble.appendChild(reasoningEl(m.reasoning, !!opts.openReasoning));
      }

      var md = document.createElement("div");
      md.className = "content" + (opts.streaming ? " cursor" : "");
      md.innerHTML = renderMarkdown(m.content);
      enhanceCodeBlocks(md);
      bubble.appendChild(md);

      if (!opts.streaming && m.sources && m.sources.length) {
        var sources = sourcesEl(m.sources);
        if (sources) bubble.appendChild(sources);
      }

      if (!opts.streaming) {
        var meta = document.createElement("div");
        meta.className = "msg-meta";
        var copy = document.createElement("button");
        copy.type = "button";
        copy.innerHTML = '<svg class="icon"><use href="#i-copy"></use></svg><span>Копировать</span>';
        copy.addEventListener("click", function () {
          copyText(m.content || "");
        });
        meta.appendChild(copy);
        if (opts.usage && opts.usage.total_tokens) {
          var tokens = document.createElement("span");
          tokens.textContent = opts.usage.total_tokens + " токенов";
          meta.appendChild(tokens);
        }
        bubble.appendChild(meta);
      }
      row.appendChild(bubble);
      if (opts.streaming) {
        row._md = md;
        row._bubble = bubble;
      }
      return row;
    }

    row.appendChild(bubble);
    return row;
  }

  function copyText(text) {
    var value = String(text == null ? "" : text);
    // The WebView clipboard API is unreliable on Android (rejected promises,
    // deprecated execCommand); prefer the native ClipboardManager.
    if (window.Native && Native.copyToClipboard) {
      try {
        Native.copyToClipboard(value);
        toast("Скопировано");
        return;
      } catch (e) { /* fall through */ }
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(value).then(function () { toast("Скопировано"); },
        function () { fallbackCopy(value); });
    } else {
      fallbackCopy(value);
    }
  }

  function fallbackCopy(text) {
    var ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
    toast(ok ? "Скопировано" : "Не удалось скопировать");
    document.body.removeChild(ta);
  }

  function renderMessages() {
    var box = $("#messages");
    box.innerHTML = "";
    var messages = S.chat && S.chat.messages ? S.chat.messages : [];
    if (!messages.length) {
      box.appendChild(emptyState());
      return;
    }
    messages.forEach(function (m) {
      box.appendChild(messageEl(m, { openReasoning: false }));
    });
    scrollToBottom(true);
  }

  function emptyState() {
    var wrap = document.createElement("div");
    wrap.className = "empty";
    var icon = document.createElement("div");
    icon.className = "empty-icon";
    icon.innerHTML = '<svg class="icon"><use href="#i-chat"></use></svg>';
    var h = document.createElement("h2");
    h.textContent = "Чат пуст";
    var p = document.createElement("p");
    var reasoning = S.thinking ? "рассуждения включены" : "рассуждения выключены";
    p.textContent = modelShortName() + " · " + reasoning;
    var hint = document.createElement("div");
    hint.className = "fine";
    hint.textContent = "Enter — отправить, Shift+Enter — новая строка.";
    wrap.appendChild(icon);
    wrap.appendChild(h);
    wrap.appendChild(p);
    wrap.appendChild(hint);
    return wrap;
  }

  // The native composer floats above the keyboard via translationY; the chat
  // scroll area gets the same bottom offset so the newest message stays visible.
  window.__imeHeight = function (cssPx) {
    var height = Math.max(0, parseInt(cssPx, 10) || 0);
    document.documentElement.style.setProperty("--ime-offset", height + "px");
    if (height > 0 && S.stick) scrollToBottom(true);
  };

  function scrollToBottom(force) {
    var box = $("#messages");
    if (!box) return;
    if (force) S.stick = true;
    if (S.stick) {
      box.scrollTop = box.scrollHeight;
    }
    updateScrollButton();
  }

  function onMessagesScroll() {
    var box = $("#messages");
    if (!box) return;
    var distance = box.scrollHeight - box.scrollTop - box.clientHeight;
    if (distance <= 6) {
      S.stick = true;
    } else if (box.scrollTop < S.lastScroll - 1) {
      S.stick = false;
    }
    S.lastScroll = box.scrollTop;
    updateScrollButton();
  }

  function updateScrollButton() {
    var box = $("#messages");
    var button = $("#scroll-bottom");
    if (!box || !button) return;
    var far = box.scrollHeight - box.scrollTop - box.clientHeight > 220;
    button.classList.toggle("hidden", !far);
  }

  // ── chat list ────────────────────────────────────────────────────────
  function refreshChats() {
    try {
      S.chats = invoke("listChats");
    } catch (e) {
      S.chats = [];
    }
    var box = $("#chat-list");
    box.innerHTML = "";
    if (!S.chats.length) {
      var empty = document.createElement("div");
      empty.className = "fine";
      empty.style.padding = "6px 8px";
      empty.textContent = "Чатов нет.";
      box.appendChild(empty);
      return;
    }
    S.chats.forEach(function (c) {
      var item = document.createElement("div");
      item.className = "chat-item" + (S.chat && S.chat.id === c.id ? " active" : "");

      var body = document.createElement("div");
      body.className = "ci-body";
      var title = document.createElement("div");
      title.className = "ci-title";
      title.textContent = c.title || "Без названия";
      var time = document.createElement("div");
      time.className = "ci-time";
      time.textContent = timeAgo(c.updated_at) + " · " + c.message_count + " сообщ.";
      body.appendChild(title);
      body.appendChild(time);

      var del = document.createElement("button");
      del.type = "button";
      del.className = "ci-del";
      del.innerHTML = '<svg class="icon"><use href="#i-delete"></use></svg>';
      del.setAttribute("aria-label", "Удалить чат");
      del.addEventListener("click", function (ev) {
        ev.stopPropagation();
        deleteChat(c);
      });

      item.addEventListener("click", function () {
        closeDrawer();
        loadChat(c.id);
      });

      item.appendChild(body);
      item.appendChild(del);
      box.appendChild(item);
    });
  }

  function deleteChat(c) {
    closeDrawer();
    confirmDialog(
      "Удалить чат?",
      "«" + (c.title || "Без названия") + "» и все сообщения будут удалены.",
      "Удалить"
    ).then(function (ok) {
      if (!ok) return;
      try {
        invoke("deleteChat", c.id);
      } catch (e) {
        toast(e.message);
        return;
      }
      if (S.chat && S.chat.id === c.id) {
        detachStream();
        S.chat = null;
        renderMessages();
        $("#chat-title").textContent = "Новый чат";
      }
      refreshChats();
      toast("Чат удалён");
    });
  }

  function loadChat(id) {
    detachStream();
    try {
      S.chat = invoke("getChat", id);
    } catch (e) {
      toast(e.message);
      return;
    }
    $("#chat-title").textContent = S.chat.title || "Чат";
    enterChat();
    renderMessages();
    refreshChats();
  }

  // ── composer ─────────────────────────────────────────────────────────
  function setStreaming(on) {
    var btn = $("#send-btn");
    btn.classList.toggle("stop", on);
    btn.innerHTML = on
      ? '<svg class="icon"><use href="#i-stop"></use></svg>'
      : '<svg class="icon"><use href="#i-send"></use></svg>';
    btn.setAttribute("aria-label", on ? "Остановить" : "Отправить");
    syncComposerState();
  }

  function wireComposer() {
    var form = $("#composer");
    var input = $("#input");
    input.addEventListener("keydown", function (e) {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
        e.preventDefault();
        form.requestSubmit ? form.requestSubmit() : form.dispatchEvent(new Event("submit", { cancelable: true }));
      }
    });

    form.addEventListener("submit", function (e) {
      e.preventDefault();
      if (S.stream) {
        stopStream();
      } else {
        sendMessage();
      }
    });
  }

  function sendMessage(textOverride, imagesOverride, fromNative) {
    var input = $("#input");
    // Blur first: it commits an active IME composition before we read the value.
    if (!fromNative && document.activeElement === input) input.blur();
    var text = typeof textOverride === "string" ? textOverride.trim() : input.value.trim();
    var images = Array.isArray(imagesOverride)
      ? imagesOverride.filter(Boolean)
      : (imagesOverride ? [imagesOverride] : []);
    if (!text && images.length) text = "Опиши изображение.";
    if ((!text && !images.length) || S.stream) {
      if (fromNative && text && window.Native && Native.restoreComposerText) {
        Native.restoreComposerText(text);
      }
      return;
    }
    if (!S.status || !S.status.has_token) {
      showTokenScreen();
      return;
    }
    if (!S.online) {
      toast("Нет доступа к интернету");
      return;
    }

    if (!S.chat) {
      S.chat = { id: "", title: "", messages: [] };
      $("#messages").innerHTML = "";
    }

    if (!fromNative) input.value = "";

    var optimistic = { role: "user", content: text, images: images };
    S.chat.messages.push(optimistic);
    var empty = $("#messages .empty");
    if (empty) empty.remove();
    $("#messages").appendChild(messageEl(optimistic));
    scrollToBottom(true);

    var start;
    try {
      start = invoke(
        "send",
        S.chat.id || "",
        text,
        S.model,
        S.effort,
        !!S.thinking,
        !!S.search,
        JSON.stringify(images)
      );
    } catch (e) {
      S.chat.messages.pop();
      var last = $("#messages .msg.user:last-child");
      if (last) last.remove();
      if (!fromNative) {
        input.value = text;
      } else if (text && window.Native && Native.restoreComposerText) {
        Native.restoreComposerText(text);
      }
      toast(e.message);
      return;
    }

    if (fromNative && window.Native && Native.clearComposer) {
      Native.clearComposer();
    }

    S.chat = start.chat;
    $("#chat-title").textContent = S.chat.title || "Чат";
    S.stream = start.stream_id;
    setStreaming(true);

    S.lastContent = "";
    S.lastReasoning = "";
    S.streamEl = messageEl({ role: "assistant", content: "", reasoning: "" }, { streaming: true });
    var stage = document.createElement("div");
    stage.className = "stream-stage hidden";
    S.streamEl._bubble.insertBefore(stage, S.streamEl._bubble.firstChild);
    S.streamStage = stage;
    S.streamReasoning = reasoningEl("", true);
    S.streamReasoning.classList.add("hidden");
    S.streamEl._bubble.insertBefore(S.streamReasoning, S.streamEl._md);
    S.streamContent = S.streamEl._md;
    $("#messages").appendChild(S.streamEl);
    scrollToBottom(true);

    pollStream(start.stream_id);
  }

  function pollStream(id) {
    var tick = function () {
      var p;
      try {
        p = invoke("poll", id);
      } catch (e) {
        finishStream({ error: e.message, cancelled: false, done: true });
        return;
      }
      paintStream(p);
      if (p.done) {
        finishStream(p);
      } else {
        S.timer = setTimeout(tick, 60);
      }
    };
    tick();
  }

  function paintStream(p) {
    if (!S.streamEl) return;
    var reasoning = p.reasoning || "";
    var content = p.content || "";
    var changed = false;

    if (reasoning && reasoning !== S.lastReasoning) {
      S.streamReasoning.classList.remove("hidden");
      S.streamReasoning._text.textContent = reasoning;
      S.lastReasoning = reasoning;
      changed = true;
    }
    if (content !== S.lastContent) {
      var stalePrefix = S.lastContent
        && content.length < S.lastContent.length
        && S.lastContent.indexOf(content) === 0;
      if (!stalePrefix) {
        S.streamContent.innerHTML = renderMarkdown(content);
        enhanceCodeBlocks(S.streamContent);
        S.lastContent = content;
        changed = true;
      }
    }
    if (changed) {
      S.streamContent.classList.add("cursor");
      scrollToBottom(false);
    }
  }

  function finishStream(p) {
    clearTimeout(S.timer);
    S.timer = null;
    if (S.streamEl) {
      if (S.streamStage) {
        S.streamStage.classList.add("hidden");
        S.streamStage = null;
      }
      S.streamContent.classList.remove("cursor");
      if (S.streamReasoning) {
        S.streamReasoning._text.textContent = p.reasoning || "";
        if (!p.reasoning) {
          S.streamReasoning.classList.add("hidden");
        } else {
          S.streamReasoning.classList.remove("open");
          var reasoningToggle = S.streamReasoning.querySelector(".reasoning-toggle");
          if (reasoningToggle) reasoningToggle.setAttribute("aria-expanded", "false");
        }
      }
      if (p.error) {
        var err = document.createElement("div");
        err.className = "msg-error";
        err.textContent = p.error;
        S.streamEl._bubble.appendChild(err);
      }
      if (!p.content && !p.error && !p.cancelled) {
        var empty = document.createElement("div");
        empty.className = "msg-error";
        empty.textContent = "Модель вернула пустой ответ.";
        S.streamEl._bubble.appendChild(empty);
      }
      var meta = document.createElement("div");
      meta.className = "msg-meta";
      if (p.cancelled) {
        var c = document.createElement("span");
        c.textContent = "Остановлено";
        meta.appendChild(c);
      }
      if (p.usage && p.usage.total_tokens) {
        var t = document.createElement("span");
        t.textContent = p.usage.total_tokens + " токенов";
        meta.appendChild(t);
      }
      if (p.chat_id) {
        var copy = document.createElement("button");
        copy.type = "button";
        copy.innerHTML = '<svg class="icon"><use href="#i-copy"></use></svg><span>Копировать</span>';
        copy.addEventListener("click", function () { copyText(p.content || ""); });
        meta.appendChild(copy);
      }
      var sourceBox = sourcesEl(p.sources);
      if (sourceBox) S.streamEl._bubble.appendChild(sourceBox);
      if (meta.children.length) S.streamEl._bubble.appendChild(meta);
    }

    var chatId = (p && p.chat_id) || (S.chat && S.chat.id);
    S.stream = null;
    S.streamEl = null;
    S.streamContent = null;
    S.streamReasoning = null;
    S.streamStage = null;
    setStreaming(false);
    $("#send-btn").disabled = !S.online;

    if (chatId) {
      try {
        S.chat = invoke("getChat", chatId);
      } catch (e) { /* keep optimistic state */ }
      $("#chat-title").textContent = (S.chat && S.chat.title) || "Чат";
    }
    refreshChats();
    scrollToBottom(false);
  }

  function stopStream() {
    if (!S.stream) return;
    try { invoke("cancel", S.stream); } catch (e) { /* stream may have just finished */ }
  }

  function detachStream() {
    clearTimeout(S.timer);
    S.timer = null;
    if (S.stream) {
      try { invoke("cancel", S.stream); } catch (e) { /* ignore */ }
    }
    S.stream = null;
    S.streamEl = null;
    S.streamContent = null;
    S.streamReasoning = null;
    S.streamStage = null;
    setStreaming(false);
  }

  // ── boot ─────────────────────────────────────────────────────────────
  function boot() {
    try {
      S.status = invoke("status");
    } catch (e) {
      fatal(e.message);
      return;
    }

    wirePermissions();
    wireTokenScreen();
    wireComposer();
    wireSettings();
    wireUpdater();

    paintTheme();
    paintTextZoom();
    applyTextZoomNow(true);
    paintCompact();
    paintModel();

    $("#menu-btn").addEventListener("click", openDrawer);
    $("#drawer-close").addEventListener("click", closeDrawer);
    $("#drawer-backdrop").addEventListener("click", closeDrawer);
    $("#drawer-new").addEventListener("click", function () {
      closeDrawer();
      detachStream();
      S.chat = null;
      $("#chat-title").textContent = "Новый чат";
      enterChat();
      renderMessages();
      focusComposer();
    });
    $("#new-chat-btn").addEventListener("click", function () {
      detachStream();
      S.chat = null;
      $("#chat-title").textContent = "Новый чат";
      enterChat();
      renderMessages();
      focusComposer();
    });
    $("#open-settings").addEventListener("click", openSettings);
    $("#settings-back").addEventListener("click", function () {
      if (S.settingsPane) {
        closeSettingsPane();
      } else {
        closeSettings();
      }
    });
    $("#settings-close").addEventListener("click", closeSettings);
    var backgroundSettings = $("#background-settings");
    if (backgroundSettings) {
      backgroundSettings.addEventListener("click", function () {
        if (window.Native && Native.openAppSettings) {
          try { Native.openAppSettings(); } catch (e) {
            toast("Не удалось открыть настройки");
          }
        } else {
          toast("Доступно только в приложении");
        }
      });
    }
    $$(".settings-cat[data-settings-pane]").forEach(function (button) {
      button.addEventListener("click", function () {
        openSettingsPane(button.getAttribute("data-settings-pane"));
      });
    });
    $("#token-howto").addEventListener("click", function () {
      var url = SOURCE_URL + "/blob/main/docs/get-token.md";
      if (window.Native && Native.openUrl) {
        try {
          Native.openUrl(url);
        } catch (e) {
          toast("Не удалось открыть ссылку");
        }
      } else {
        window.open(url, "_blank");
      }
    });
    $$("#settings-auth-mode button").forEach(function (button) {
      button.addEventListener("click", function () {
        applyAuthMode(button.getAttribute("data-settings-auth"), true);
        paintSettingsTokenMode();
        refreshSettingsTokenButton();
        toast(S.authMode === "web" ? "Веб-токен" : "API-ключ");
      });
    });
    $("#settings-token-input").addEventListener("input", refreshSettingsTokenButton);
    $("#settings-token-save").addEventListener("click", saveSettingsToken);
    $("#reasoning-chip").addEventListener("click", function () {
      S.thinking = !S.thinking;
      localStorage.setItem("thinking", S.thinking ? "1" : "0");
      paintModel();
    });
    $("#search-chip").addEventListener("click", function () {
      S.search = !S.search;
      localStorage.setItem("search", S.search ? "1" : "0");
      paintModel();
    });
    $("#search-toggle").addEventListener("change", function () {
      S.search = this.checked;
      localStorage.setItem("search", S.search ? "1" : "0");
      paintModel();
    });

    $("#image-viewer-close").addEventListener("click", closeImageViewer);
    $("#image-viewer").addEventListener("click", function (e) {
      if (e.target === this || e.target.id === "image-viewer-img") closeImageViewer();
    });

    $("#dialog-cancel").addEventListener("click", function () { closeDialog(false); });
    $("#dialog-ok").addEventListener("click", function () { closeDialog(true); });
    $("#dialog-scrim").addEventListener("click", function (e) {
      if (e.target === this) closeDialog(false);
    });

    $("#messages").addEventListener("scroll", onMessagesScroll);
    $("#scroll-bottom").addEventListener("click", function () { scrollToBottom(true); });
    $("#messages").addEventListener("click", function (e) {
      var target = e.target;
      if (!target || !target.closest) return;
      if (target.classList && target.classList.contains("msg-image")) {
        openImageViewer(target.src);
        return;
      }
      if (target.id === "image-viewer" || target.id === "image-viewer-img") {
        closeImageViewer();
        return;
      }
      var runButton = target.closest(".code-run");
      if (runButton) {
        var runBlock = runButton.closest(".code-block");
        var runCode = runBlock ? runBlock.querySelector("pre code") : null;
        if (runCode) {
          if (window.Native && Native.previewHtml) {
            try {
              Native.previewHtml(runCode.textContent || "");
            } catch (e) {
              toast("Не удалось открыть предпросмотр");
            }
          } else {
            toast("Предпросмотр доступен только в приложении");
          }
        }
        return;
      }
      var copyButton = target.closest(".code-copy");
      if (copyButton) {
        var block = copyButton.closest(".code-block");
        var code = block ? block.querySelector("pre code") : null;
        if (code) copyText(code.textContent || "");
        return;
      }
      var head = target.closest(".code-head");
      if (head) toggleCodeBlock(head.closest(".code-block"), head);
    });
    $("#messages").addEventListener("keydown", function (e) {
      if (e.key !== "Enter" && e.key !== " ") return;
      var target = e.target;
      if (!target || !target.closest) return;
      if (target.closest(".code-copy") || target.closest(".code-run")) return;
      var head = target.closest(".code-head");
      if (head) {
        e.preventDefault();
        toggleCodeBlock(head.closest(".code-block"), head);
      }
    });

    window.addEventListener("resize", function () {
      if (S.stick) {
        requestAnimationFrame(function () { scrollToBottom(true); });
      }
    });

    if (window.matchMedia) {
      var colorQuery = window.matchMedia("(prefers-color-scheme: light)");
      var onColorChange = function () { if (S.theme === "system") paintTheme(); };
      if (colorQuery.addEventListener) colorQuery.addEventListener("change", onColorChange);
      else if (colorQuery.addListener) colorQuery.addListener(onColorChange);
    }

    $("#sheet-revalidate").addEventListener("click", function () {
      var btn = this;
      btn.disabled = true;
      btn.textContent = "Проверяю…";
      setTimeout(function () {
        try {
          S.status = invoke("revalidate");
          toast("Ключ действителен");
        } catch (e) {
          toast(e.message);
        }
        btn.disabled = false;
        btn.textContent = "Проверить";
      }, 60);
    });

    $("#sheet-clear").addEventListener("click", function () {
      confirmDialog(
        "Удалить ключ?",
        "История чатов останется на устройстве.",
        "Удалить"
      ).then(function (ok) {
        if (!ok) return;
        try {
          S.status = invoke("clearToken");
        } catch (e) {
          toast(e.message);
          return;
        }
        S.online = S.status.online !== false;
        detachStream();
        S.chat = null;
        localStorage.setItem("serverEnabled", "0");
        S.server = { running: false, port: 0, key: "", base_url: "" };
        showTokenScreen();
        paintOnline();
        toast("Ключ удалён");
      });
    });

    var info = nativeJson("appInfo");
    S.version = info.version || "";
    $("#drawer-version").textContent = S.version ? "v" + S.version : "";
    $("#sheet-version").textContent = S.version ? "v" + S.version : "";

    $("#onboard-continue").addEventListener("click", function () {
      localStorage.setItem("onboarded", "1");
      if (S.status && S.status.has_token) enterChat(); else showTokenScreen();
    });

    S.online = S.status.online !== false;
    applyAuthMode(S.status.has_token ? S.status.mode : S.authMode, false);
    paintOnline();
    refreshServerStatus();
    ensureLocalService();
    $("#cf-host").value = localStorage.getItem("cfHost") || "";
    paintCfMode();
    refreshTunnelStatus();

    if (!S.status.has_token) {
      if (!localStorage.getItem("onboarded")) {
        show("screen-onboarding");
        refreshPermissions();
      } else {
        showTokenScreen();
      }
    } else {
      refreshChats();
      if (S.chats.length) {
        loadChat(S.chats[0].id);
      } else {
        enterChat();
      }
    }
    maybeStartLocalServer();
  }

  window.__nativeComposerReady = function () {
    if (!window.Native || !window.Native.setComposerVisible) return;
    NATIVE_COMPOSER = true;
    document.body.classList.add("native-composer");
    syncNativeComposer();
    syncComposerState();
    initUpdater();
  };

  window.__nativeSend = function (raw) {
    var payload;
    try {
      payload = JSON.parse(raw);
    } catch (e) {
      return;
    }
    var images = Array.isArray(payload.images)
      ? payload.images
      : (payload.image ? [payload.image] : []);
    sendMessage(payload.text || "", images, true);
  };

  window.__tunnelStatus = function (raw) {
    try {
      paintTunnel(JSON.parse(raw));
    } catch (e) { /* ignore */ }
  };

  window.__nativeStop = function () {
    stopStream();
  };

  window.__onAndroidBack = function () {
    if (!$("#image-viewer").classList.contains("hidden")) { closeImageViewer(); return true; }
    if (!$("#dialog-scrim").classList.contains("hidden")) { closeDialog(false); return true; }
    if ($("#screen-settings").classList.contains("active")) {
      if (S.settingsPane) {
        showSettingsRoot();
      } else {
        closeSettings();
      }
      return true;
    }
    if ($("#drawer").classList.contains("open")) { closeDrawer(); return true; }
    return false;
  };

  window.__onResume = function () {
    if ($("#screen-onboarding").classList.contains("active")) {
      refreshPermissions();
    }
    paintOnline();
    ensureLocalService();
  };

  document.addEventListener("DOMContentLoaded", boot);
})();
