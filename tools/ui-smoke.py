"""Headless smoke test for the WebView UI with a mocked native bridge.

Run: python3 tools/ui-smoke.py   (needs `pip install playwright` + chromium)
Screenshots land in screenshots/.
"""
import json
import os
import threading
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "app/app/src/main/assets/web"
SHOTS = ROOT / "screenshots"
SHOTS.mkdir(exist_ok=True)

class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass


handler = partial(QuietHandler, directory=str(WEB))
server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
BASE = "http://127.0.0.1:%d" % server.server_address[1]

MOCK = r"""
(() => {
  const ok = d => JSON.stringify({ ok: true, data: d });
  const bad = (kind, message) => JSON.stringify({ ok: false, error: { kind, message } });
  const saved = JSON.parse(localStorage.getItem('__mock') || '{}');
  const st = {
    token: saved.token || null,
    mode: saved.mode || 'api',
    chats: saved.chats || [],
    agentFiles: saved.agentFiles || {},
    perms: saved.perms || { notifications: 'missing', battery: 'optimized' },
    stream: null,
    seq: 1
  };
  function persist() {
    localStorage.setItem('__mock', JSON.stringify({ token: st.token, mode: st.mode, chats: st.chats, perms: st.perms, agentFiles: st.agentFiles }));
  }
  function status() {
    return {
      has_token: !!st.token,
      mode: st.mode,
      online: st.online !== false,
      model: 'deepseek-flash',
      model_label: 'DeepSeek V4.1 Flash 1017',
      model_version: 'DeepSeek-V4.1-Flash-1017',
      base_url: 'https://api.deepseek.com',
      models: st.token ? ['deepseek-flash', 'deepseek-v4-pro'] : [],
      balance: st.token && st.mode === 'api' ? { available: true, currency: 'USD', total: '12.34', granted: '2.00', topped_up: '10.34' } : null
    };
  }
  const REASON = ['Пользователь просит ', 'сравнить процессы ', 'и потоки. ', 'Отвечу структурно: ', 'сначала различие ', 'в памяти, затем ', 'в стоимости переключения.'];
  const CONTENT = ['Процесс — ', 'это изолированная ', 'программа со своей ', 'адресной памятью.\n\n', '**Поток** — ', 'единица выполнения ', 'внутри процесса: ', 'он делит память ', 'с остальными ', 'потоками.\n\n', 'Ссылка: https://example.com/', 'a'.repeat(160), '\n\n', '### Заголовок\n', '- пункт один\n', '- пункт два с *курсивом*\n', '\n> цитата\n\n', '[ссылка](https://example.com)\n', '```rust\n', 'fn main() {}\n', '```', '\n| Метрика | Процесс | Поток |\n', '|---|---:|---|\n', '| Память | своя | общая |\n', '| Переключение | дорого | дёшево |\n'];
  window.Native = {
    status: () => ok(status()),
    setToken: (t, mode) => {
      if (mode === 'web') {
        if (!t || t.indexOf('web') === -1) {
          return bad('unauthorized', 'Веб-токен недействителен или истёк.');
        }
        st.token = t; st.mode = 'web'; persist();
        return ok(status());
      }
      if (t !== 'sk-test-token-1234567890') {
        return bad('unauthorized', 'Токен недействителен или отозван.');
      }
      st.token = t; st.mode = 'api'; persist();
      return ok(status());
    },
    revalidate: () => st.token ? ok(status()) : bad('unauthorized', 'Токен ещё не добавлен'),
    clearToken: () => { st.token = null; st.mode = 'api'; persist(); return ok(status()); },
    setOnline: on => { st.online = !!on; return ok(status()); },
    newChat: kind => {
      const k = kind === 'agent' ? 'agent' : 'chat';
      const c = { id: 'c' + (st.seq++), kind: k, title: '', created_at: Date.now(), updated_at: Date.now(), messages: [] };
      st.chats.unshift(c); persist(); return ok(c);
    },
    listChats: kind => {
      const k = kind === 'agent' ? 'agent' : 'chat';
      return ok(st.chats
        .filter(c => (c.kind || 'chat') === k)
        .map(c => ({ id: c.id, title: c.title || 'Без названия', updated_at: c.updated_at, message_count: c.messages.length })));
    },
    getChat: id => {
      const c = st.chats.find(x => x.id === id);
      return c ? ok(c) : bad('not_found', 'Чат не найден');
    },
    agentRootInfo: () => ok({ configured: true, name: 'LocalApiDeepSeek', uri: 'content://mock/tree' }),
    sandboxStatus: () => ok({ installed: true, installing: false, running: false, version: 'mock', log: '' }),
    installSandbox: () => {},
    startSandbox: () => {},
    stopSandbox: () => {},
    sandboxWrite: () => {},
    chooseAgentRoot: () => {},
    ensureAgentFolder: () => ok({ name: 'AI-заголовок' }),
    openAgentFolder: () => {},
    renameAgentFolder: () => ok(true),
    useInternalAgentWorkspace: () => ok('ok'),
    generateTitle: (chatId, text) => {
      const c = st.chats.find(x => x.id === chatId);
      if (!c) return ok(null);
      c.title = 'AI-заголовок';
      persist();
      setTimeout(() => {
        try { window.__titleUpdated(chatId, c.title); } catch (e) {}
      }, 0);
      return ok(null);
    },
    pickAgentZip: chatId => {
      st.agentFiles[chatId] = [
        { name: 'src/main.py', size: 1536 },
        { name: 'README.md', size: 240 }
      ];
      persist();
      setTimeout(() => {
        try { window.__agentFilesUpdated(chatId); } catch (e) {}
      }, 0);
    },
    listAgentFiles: chatId => ok(st.agentFiles[chatId] || []),
    readAgentFile: (chatId, name) => {
      const text = name.endsWith('.py') ? "print('hello')\n" : "# Readme\nProject notes\n";
      return ok({ name: name, text: text, truncated: false, binary: false, size: text.length });
    },
    readAgentMedia: (chatId, name) => ok({
      name: name, size: 68, mime: 'image/png',
      data: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII='
    }),
    deleteAgentFile: (chatId, name) => {
      st.agentFiles[chatId] = (st.agentFiles[chatId] || []).filter(f => f.name !== name);
      persist(); return ok('deleted');
    },
    clearAgentWorkspace: chatId => {
      delete st.agentFiles[chatId]; persist(); return ok('cleared');
    },
    deleteChat: id => { st.chats = st.chats.filter(x => x.id !== id); persist(); return ok(null); },
    send: function (chatId, text, model, effort, thinking, search, images, systemPrompt) {
      if (arguments.length !== 8) throw new Error('send bridge args=' + arguments.length);
      window.__lastSystemPrompt = systemPrompt;
      let c = st.chats.find(x => x.id === chatId);
      if (!c) {
        c = { id: 'c' + (st.seq++), kind: 'chat', title: text.slice(0, 48), created_at: Date.now(), updated_at: Date.now(), messages: [] };
        st.chats.unshift(c);
      }
      c.messages.push({ role: 'user', content: text, ts: Date.now() });
      c.updated_at = Date.now();
      if (text.indexOf('[TOOL_RESULT]') === 0) window.__lastSentImages = images;
      st.stream = { chatId: c.id, ri: 0, ci: 0, reasoning: '', content: '', chat: c,
        search: !!search, browser: text.indexOf('BROWSER_TEST') >= 0 };
      persist();
      return ok({ stream_id: 7, chat: c });
    },
    poll: () => {
      const s = st.stream;
      if (!s) return bad('not_found', 'Поток не найден');
      if (s.browser && !s.browserSent) {
        s.browserSent = true;
        s.ri = REASON.length;
        s.ci = CONTENT.length;
        s.reasoning = '';
        s.content = '[[BROWSER: screenshot]]';
      }
      let progressed = false;
      if (s.ri < REASON.length) { s.reasoning += REASON[s.ri++]; progressed = true; }
      else if (s.ci < CONTENT.length) { s.content += CONTENT[s.ci++]; progressed = true; }
      const done = s.ri >= REASON.length && s.ci >= CONTENT.length;
      const sources = s.search ? [{ title: 'Gemini models', url: 'https://example.com/gemini', site: 'Example', date: '2026-11-01' }] : [];
      if (done && !s.saved) {
        s.saved = true;
        s.chat.messages.push({ role: 'assistant', content: s.content, reasoning: s.reasoning, sources, ts: Date.now() });
        s.chat.updated_at = Date.now();
        st.chats = [s.chat].concat(st.chats.filter(x => x.id !== s.chat.id));
        persist();
      }
      return ok({
        stream_id: 7, chat_id: s.chatId, done: done,
        content: s.content, reasoning: s.reasoning,
        cancelled: false, error: null, error_kind: null,
        usage: done ? { prompt_tokens: 24, completion_tokens: 61, total_tokens: 85 } : null,
        stage: done ? null : (s.ci < 2 ? 'searching' : 'writing'),
        sources: done ? sources : []
      });
    },
    serverStatus: () => ok(st.server || { running: false, port: 0, key: '', base_url: '' }),
    startServer: port => {
      st.server = { running: true, port: Number(port), key: 'localkey-1234567890', base_url: 'http://127.0.0.1:' + port + '/v1' };
      return ok(st.server);
    },
    stopServer: () => {
      st.server = { running: false, port: 0, key: '', base_url: '' };
      return ok(null);
    },
    setTextZoom: () => '{}',
    cancel: () => {
      if (st.stream && !st.stream.saved) {
        st.stream.saved = true;
        st.stream.chat.messages.push({ role: 'assistant', content: st.stream.content, reasoning: st.stream.reasoning, ts: Date.now() });
        persist();
      }
      return ok(null);
    },
    permissions: () => JSON.stringify(st.perms),
    requestNotifications: () => { st.perms.notifications = 'granted'; persist(); return JSON.stringify(st.perms); },
    requestBattery: () => { st.perms.battery = 'ignoring'; persist(); return JSON.stringify(st.perms); },
    readClipboard: () => 'sk-test-token-1234567890',
    openUrl: url => { window.__openedUrl = url; return '{}'; },
    browserCommand: (action, arg, id) => {
      setTimeout(() => {
        try {
          window.__browserResult(id, JSON.stringify({
            ok: true, action: action, url: 'https://example.com', title: 'Example',
            text: 'Example page text', links: [],
            screenshot: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII='
          }));
        } catch (e) { /* ignore */ }
      }, 0);
    },
    restoreComposerText: text => { window.__restored = text; },
    appInfo: () => JSON.stringify({ version: '1.11.7', package: 'dev.localapi.deepseek' })
  };
})();
"""

errors = []
with sync_playwright() as p:
    browser = p.chromium.launch()
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=2,
                              is_mobile=True, has_touch=True, color_scheme="dark")
    ctx.grant_permissions(["clipboard-read", "clipboard-write"])
    page = ctx.new_page()
    page.on("console", lambda m: (errors.append(f"console.{m.type}: {m.text}"), print("CONSOLE", m.type, m.text)) if m.type == "error" else None)
    page.on("pageerror", lambda e: (errors.append(f"pageerror: {e}"), print("PAGEERROR", e)))
    page.add_init_script(MOCK)

    page.goto(BASE + "/index.html")
    page.wait_for_selector("#screen-onboarding.active")
    page.screenshot(path=f"{SHOTS}/01-onboarding.png")

    page.click('button[data-perm="notifications"]')
    page.click('button[data-perm="battery"]')
    page.wait_for_timeout(600)
    assert "Разрешено" in page.inner_text("#perm-notifications"), page.inner_text("#perm-notifications")
    assert "Отключено" in page.inner_text("#perm-battery")
    page.screenshot(path=f"{SHOTS}/02-onboarding-granted.png")

    page.click("#onboard-continue")
    page.wait_for_selector("#screen-token.active")
    page.wait_for_timeout(300)
    page.evaluate("window.__restored = ''; window.__nativeSend(JSON.stringify({text: 'черновик', images: []}))")
    assert page.evaluate("window.__restored") == "черновик"
    page.screenshot(path=f"{SHOTS}/03-token.png")

    page.fill("#token-input", "sk-invalid-token")
    page.click("#token-save")
    page.wait_for_selector("#token-error:not(.hidden)", timeout=5000)
    page.screenshot(path=f"{SHOTS}/04-token-error.png")

    page.fill("#token-input", "sk-test-token-1234567890")
    page.click("#token-save")
    page.wait_for_selector("#token-result:not(.hidden)", timeout=5000)
    assert "12.34" in page.inner_text("#result-balance")
    page.wait_for_timeout(350)
    page.screenshot(path=f"{SHOTS}/05-token-success.png")

    page.click("#token-open")
    page.wait_for_selector("#screen-chat.active")
    page.wait_for_selector(".empty h2")
    page.screenshot(path=f"{SHOTS}/06-chat-empty.png")

    page.click("#system-btn")
    page.wait_for_selector("#right-drawer.open")
    page.wait_for_timeout(500)
    page.click("#search-chip", force=True)
    assert "active" in (page.get_attribute("#search-chip", "class") or "")
    page.click("#right-drawer-close")
    page.wait_for_timeout(380)
    page.fill("#input", "Объясни разницу между процессами и потоками")
    page.click("#send-btn")
    page.wait_for_timeout(260)
    page.screenshot(path=f"{SHOTS}/07-chat-streaming.png")
    page.wait_for_timeout(2400)
    assert page.locator(".reasoning").count() == 1
    assert page.locator(".reasoning .reasoning-toggle svg").count() == 1
    page.wait_for_function("document.querySelector('#chat-title').textContent.includes('AI-заголовок')", timeout=3000)
    assert "Размышления ·" in page.inner_text(".reasoning-toggle")
    assert "tok/s" in page.inner_text(".msg-meta")
    assert "ответ" in page.inner_text(".msg-meta")
    assert page.locator(".msg.user .msg-author").first.text_content() == "User"
    assert page.locator(".msg.assistant .msg-author").first.text_content() == "Model"
    assert "Контекст ≈" in page.inner_text("#context-meter")
    page.evaluate("document.querySelector('#messages').scrollTop = 0")
    page.wait_for_timeout(200)
    page.screenshot(path=f"{SHOTS}/08b-chat-top.png")
    page.locator(".reasoning-toggle").first.click()
    page.wait_for_timeout(380)
    assert "open" in (page.get_attribute(".reasoning", "class") or "")
    page.locator(".reasoning-toggle").first.click()
    page.wait_for_timeout(380)
    assert "open" not in (page.get_attribute(".reasoning", "class") or "")
    page.locator(".code-head").first.click()
    page.wait_for_timeout(380)
    assert "collapsed" in (page.get_attribute(".code-block", "class") or "")
    page.locator(".code-head").first.click()
    page.wait_for_timeout(380)
    assert "collapsed" not in (page.get_attribute(".code-block", "class") or "")
    assert page.locator(".sources a").count() == 1
    assert "Gemini models" in page.inner_text(".sources")
    assert page.locator(".content h3").count() == 1
    assert page.locator(".content li").count() >= 2
    assert page.locator(".content em").count() == 1
    assert page.locator('.content a[href="https://example.com"]').count() == 1
    assert page.locator(".code-block .code-copy").count() == 1
    assert page.locator(".code-block .code-gutter").first.inner_text().strip() == "1"
    assert "строка" in page.inner_text(".code-block .code-count")
    assert page.locator(".code-block .hljs-keyword").count() >= 1
    assert page.locator(".md-table").count() == 1
    assert page.locator(".md-table th").count() == 3
    assert page.locator(".md-table tr").count() == 3
    assert "общая" in page.inner_text(".md-table")
    page.locator(".md-table").scroll_into_view_if_needed()
    page.wait_for_timeout(300)
    page.screenshot(path=f"{SHOTS}/08a-table.png")
    page.locator(".code-copy").first.click()
    page.wait_for_timeout(200)
    assert page.is_visible("#toast")
    assert page.inner_text("#toast") == "Скопировано"
    page.screenshot(path=f"{SHOTS}/08-chat-done.png")

    # scrolling up during generation must not be yanked back to the bottom
    page.fill("#input", "Второй вопрос")
    page.click("#send-btn")
    page.wait_for_timeout(300)
    page.evaluate("document.querySelector('#messages').scrollTop = 0")
    page.wait_for_timeout(500)
    top = page.evaluate("document.querySelector('#messages').scrollTop")
    assert top < 40, f"stream yanked the list to {top}"
    assert page.is_visible("#scroll-bottom")
    page.wait_for_timeout(1800)

    page.click("#menu-btn")
    page.wait_for_selector("#drawer.open")
    page.wait_for_timeout(350)
    assert page.locator("#chat-list .ci-title").first.inner_text() == "AI-заголовок"
    page.screenshot(path=f"{SHOTS}/09-drawer.png")
    page.click("#drawer-close")

    page.click("#new-chat-btn")
    page.click("#menu-btn")
    page.click("#open-settings")
    page.wait_for_timeout(450)
    page.wait_for_timeout(1900)
    page.screenshot(path=f"{SHOTS}/10-settings.png")

    # Model controls live in the chat now; the settings catalog only has
    # device/server/account groups.
    page.click("#settings-close")
    page.wait_for_timeout(300)
    page.click("#system-btn")
    page.wait_for_selector("#right-drawer.open")
    page.wait_for_timeout(500)
    assert page.is_visible("#reasoning-chip")
    if "active" in (page.get_attribute("#reasoning-chip", "class") or ""):
        page.click("#reasoning-chip", force=True)
        page.wait_for_timeout(150)
    assert "active" not in (page.get_attribute("#reasoning-chip", "class") or "")
    assert "выкл" in page.inner_text("#reasoning-chip-label")
    page.click("#model-chip", force=True)
    page.wait_for_selector("#model-scrim:not(.hidden)")
    page.click('#model-select button[data-model="deepseek-v4-pro"]')
    assert "V4 Pro" in (page.text_content("#sheet-model") or "")
    assert page.is_checked("#search-toggle")
    assert page.is_hidden("#effort-setting")
    page.wait_for_timeout(340)
    page.screenshot(path=f"{SHOTS}/10a-model-sheet.png")
    page.click("#model-done")
    assert "V4 Pro" in page.inner_text("#model-chip-label")
    page.click("#reasoning-chip", force=True)
    page.wait_for_timeout(150)
    assert "active" in (page.get_attribute("#reasoning-chip", "class") or "")
    assert "вкл" in page.inner_text("#reasoning-chip-label")
    page.click("#model-chip", force=True)
    page.wait_for_selector("#model-scrim:not(.hidden)")
    assert page.is_visible("#effort-setting")
    page.click("#model-done")

    # System instruction is a right-side drawer and travels with the next send.
    page.wait_for_selector("#right-drawer.open")
    page.wait_for_timeout(380)
    page.fill("#system-prompt", "Отвечай кратко")
    page.screenshot(path=f"{SHOTS}/10b-system-drawer.png")
    page.click("#system-save")
    page.wait_for_timeout(380)
    assert page.evaluate("localStorage.getItem('systemPrompt')") == "Отвечай кратко"
    page.fill("#input", "Третий вопрос")
    page.click("#send-btn")
    page.wait_for_timeout(220)
    system_prompt = page.evaluate("window.__lastSystemPrompt")
    assert "Отвечай кратко" in system_prompt
    assert "LaTeX" in system_prompt
    assert "[[font:serif|" in system_prompt
    page.wait_for_timeout(1800)

    page.click("#menu-btn")
    page.click("#open-settings")
    page.wait_for_timeout(450)
    page.click('.settings-cat[data-settings-pane="token"]')
    assert page.is_visible("#settings-auth-mode")
    assert page.is_visible("#sheet-mode")
    assert page.is_visible("#api-reasoning-toggle")
    page.uncheck("#api-reasoning-toggle")
    assert page.inner_text("#api-reasoning-note") == "Выключен"
    page.check("#api-reasoning-toggle")
    assert page.inner_text("#api-reasoning-note") == "DeepThink включён"
    page.screenshot(path=f"{SHOTS}/10c-api-reasoning.png")
    page.click("#settings-back")
    page.click('.settings-cat[data-settings-pane="ui"]')
    page.click('#theme-select button[data-theme-choice="light"]')
    assert page.get_attribute("html", "data-theme") == "light"
    page.click('#theme-select button[data-theme-choice="dark"]')
    assert page.get_attribute("html", "data-theme") == "dark"

    page.locator("#text-slider").evaluate(
        "el => { el.value = '115'; el.dispatchEvent(new Event('input', { bubbles: true })); }"
    )
    page.wait_for_timeout(120)
    assert page.evaluate("localStorage.getItem('textZoom')") == "115"
    assert page.inner_text("#text-value") == "115%"
    page.click("#settings-back")
    assert "текст 115%" in page.inner_text("#cat-sub-ui")

    page.click('.settings-cat[data-settings-pane="server"]')
    page.check("#server-toggle")
    page.wait_for_timeout(150)
    assert page.is_visible("#server-info")
    assert "127.0.0.1" in page.inner_text("#server-url")
    assert page.inner_text("#server-model") == "deepseek-v4-pro"
    page.click("#server-copy-model")
    page.wait_for_timeout(150)
    assert page.inner_text("#toast") == "Скопировано"
    page.evaluate("document.querySelector('.settings-scroll').scrollTop = 99999")
    page.wait_for_timeout(300)
    page.screenshot(path=f"{SHOTS}/14-local-server.png")
    page.uncheck("#server-toggle")
    page.click("#settings-back")

    # offline switch blocks sending
    page.click('.settings-cat[data-settings-pane="system"]')
    page.uncheck("#online-toggle")
    page.click("#settings-close")
    page.wait_for_timeout(200)
    assert page.is_visible("#offline-banner")
    assert page.is_disabled("#send-btn")
    page.screenshot(path=f"{SHOTS}/15-offline.png")
    page.click("#menu-btn")
    page.click("#open-settings")
    page.click('.settings-cat[data-settings-pane="system"]')
    page.check("#online-toggle")
    page.click("#settings-close")
    page.wait_for_timeout(200)
    assert page.is_hidden("#offline-banner")
    assert page.is_disabled("#send-btn")
    page.fill("#input", "Проверка")
    assert page.is_enabled("#send-btn")
    page.fill("#input", "")
    assert page.is_disabled("#send-btn")

    # light theme screenshot
    page.click("#menu-btn")
    page.click("#open-settings")
    page.click('.settings-cat[data-settings-pane="ui"]')
    page.click('#theme-select button[data-theme-choice="light"]')
    page.click("#settings-close")
    page.wait_for_timeout(300)
    page.screenshot(path=f"{SHOTS}/11-light-chat.png")
    page.click("#menu-btn")
    page.click("#open-settings")
    page.click('.settings-cat[data-settings-pane="ui"]')
    page.click('#theme-select button[data-theme-choice="dark"]')
    page.click("#settings-close")

    # web-token mode: token screen copy, then chat with DeepThink instead of effort
    page.evaluate("""() => {
        localStorage.setItem('authMode', 'web');
        const m = JSON.parse(localStorage.getItem('__mock'));
        m.token = null; m.mode = 'api';
        localStorage.setItem('__mock', JSON.stringify(m));
    }""")
    page.reload()
    page.wait_for_selector("#screen-token.active")
    page.wait_for_timeout(300)
    assert page.is_visible("#web-warning")
    page.screenshot(path=f"{SHOTS}/12-web-token.png")

    # the paste button must enable the submit button (regression: it used not to)
    page.click("#token-paste")
    page.wait_for_timeout(150)
    assert page.is_enabled("#token-save")
    page.fill("#token-input", "web-token-abcdef0123456789")
    page.click("#token-save")
    page.wait_for_selector("#token-result:not(.hidden)", timeout=5000)
    assert "веб-сессия" in page.inner_text("#result-mode")
    page.click("#token-open")
    page.wait_for_selector("#screen-chat.active")
    page.click("#system-btn")
    page.wait_for_selector("#right-drawer.open")
    page.wait_for_timeout(500)
    assert page.is_hidden("#model-chip")
    assert page.is_visible("#reasoning-chip")
    before = "active" in (page.get_attribute("#reasoning-chip", "class") or "")
    page.click("#reasoning-chip", force=True)
    page.wait_for_timeout(150)
    after = "active" in (page.get_attribute("#reasoning-chip", "class") or "")
    assert after != before
    assert ("вкл" in page.inner_text("#reasoning-chip-label")) == after
    page.click("#right-drawer-close")
    page.wait_for_timeout(380)
    page.fill("#input", "Проверка веб-режима")
    page.click("#send-btn")
    page.wait_for_timeout(1900)

    overflow = page.evaluate("""() => {
        const box = document.querySelector('#messages');
        return {
            box: box.scrollWidth - box.clientWidth,
            doc: document.documentElement.scrollWidth - document.documentElement.clientWidth
        };
    }""")
    assert overflow["box"] <= 1, overflow
    assert overflow["doc"] <= 1, overflow
    page.screenshot(path=f"{SHOTS}/13-web-chat.png")

    # delete chat uses the in-app MD3 dialog (window.confirm is unavailable in WebView)
    page.click("#menu-btn")
    page.wait_for_timeout(400)
    box = page.locator(".ci-del").first.bounding_box()
    page.mouse.click(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    page.wait_for_selector("#dialog-scrim:not(.hidden)")
    page.wait_for_timeout(300)
    page.screenshot(path=f"{SHOTS}/16-dialog.png")
    page.click("#dialog-cancel")
    assert page.is_hidden("#dialog-scrim")
    page.click("#menu-btn")
    page.wait_for_timeout(400)
    box = page.locator(".ci-del").first.bounding_box()
    page.mouse.click(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    page.click("#dialog-ok")
    page.wait_for_timeout(300)
    assert page.inner_text("#chat-title") == "Новый чат"

    # agent mode: separate history, ZIP workspace and tools catalog, no execution
    page.click("#menu-btn")
    page.wait_for_timeout(300)
    page.click("#agent-mode")
    page.wait_for_timeout(400)
    assert page.is_visible("#agent-bar")
    assert page.inner_text("#agent-mode-label") == "Обычные чаты"
    page.evaluate("window.__sandboxStarted && window.__sandboxStarted()")
    page.wait_for_selector(".sandbox-notice.started")
    page.evaluate("window.__sandboxExit && window.__sandboxExit(0)")
    page.wait_for_timeout(100)
    assert page.locator(".sandbox-notice.stopped").count() == 1
    assert "Песочница остановлена" in page.inner_text(".sandbox-notice.stopped")
    page.evaluate("window.__nativeAgentZip && window.__nativeAgentZip()")
    page.wait_for_selector(".agent-file", timeout=3000)
    assert page.locator(".agent-file").count() == 2
    page.locator(".agent-file button").first.click()
    page.wait_for_timeout(250)
    assert page.locator(".agent-file").count() == 1
    page.click("#system-btn")
    page.wait_for_selector("#right-drawer.open")
    page.wait_for_timeout(500)
    page.click("#right-agent-tools")
    page.wait_for_selector("#tools-scrim:not(.hidden)")
    page.wait_for_timeout(350)
    assert page.locator("#tools-sandbox").count() == 0
    assert page.locator(".tools-list").count() == 0
    assert "Песочница Ubuntu" not in page.inner_text("#tools-scrim")
    assert "Инструменты агента" in page.inner_text("#tools-scrim")
    assert page.locator("#tools-done").count() == 0
    page.screenshot(path=f"{SHOTS}/17-agent-tools.png")
    page.click("#tools-close")
    assert page.is_hidden("#tools-scrim")
    page.evaluate("document.querySelector('#right-drawer-close').click()")
    page.wait_for_timeout(380)
    page.screenshot(path=f"{SHOTS}/17-agent.png")
    page.fill("#input", "Что в файлах?")
    page.click("#send-btn")
    page.wait_for_timeout(500)
    assert "README.md" in page.evaluate("window.__lastSystemPrompt || ''")
    assert "# Readme" in page.evaluate("window.__lastSystemPrompt || ''")

    # Browser tool loop: an assistant [[BROWSER]] directive must execute through
    # the native bridge, render one collapsible browser block and pass the
    # screenshot into the next model request.
    page.wait_for_selector("#send-btn:not(.stop)", timeout=5000)
    page.fill("#input", "BROWSER_TEST")
    page.click("#send-btn")
    page.wait_for_selector(".tool-block .tool-label", timeout=5000)
    page.wait_for_timeout(900)
    assert "БРАУЗЕР" in page.inner_text(".tool-block")
    if page.locator(".tool-block .tool-body").is_hidden():
        page.click(".tool-block .tool-toggle")
    assert page.locator(".tool-block .tool-body").is_visible()
    assert "Example page text" in page.inner_text(".tool-block")
    assert "data:image/png;base64" in page.evaluate("window.__lastSentImages || ''")
    page.screenshot(path=f"{SHOTS}/17b-browser.png")

    page.click("#menu-btn")
    page.wait_for_timeout(300)
    page.click("#agent-mode")
    page.wait_for_timeout(400)
    assert page.is_hidden("#agent-bar")
    assert page.inner_text("#agent-mode-label") == "Режим агента"

    # Reopening a saved agent session must restore command/result blocks, not
    # leave empty assistant bubbles where [[EXEC]] directives were stripped.
    page.evaluate("""() => {
      const m = JSON.parse(localStorage.getItem('__mock'));
      m.chats.unshift({
        id: 'ctoolhistory', kind: 'agent', title: 'Tool history',
        created_at: Date.now() - 1000, updated_at: Date.now() + 1000,
        messages: [
          { role: 'user', content: 'Проверь папку', ts: 1, images: [] },
          { role: 'assistant', content: '[[EXEC: ls -la /workspace]]', reasoning: 'Проверяю папку', sources: [], ts: 2 },
          { role: 'user', content: '[TOOL_RESULT]\\nexit code: 0\\nREADME.md\\n\\nПродолжай. Если нужна ещё команда — ответь строкой [[EXEC: ...]].', ts: 3, images: [] },
          { role: 'assistant', content: 'Готово $x^2$ \\\\[ r=\\\\frac{1}{2} \\\\] [[font:serif|Шрифт]] [[SEND: /workspace/pic.png]] [[BROWSE: test query]]', reasoning: 'Файл найден', sources: [], ts: 4 }
        ]
      });
      localStorage.setItem('__mock', JSON.stringify(m));
    }""")
    page.reload()
    page.wait_for_timeout(700)
    page.click("#menu-btn")
    page.wait_for_timeout(300)
    page.click("#agent-mode")
    page.wait_for_selector(".msg.tool", timeout=3000)
    assert page.locator(".msg.tool").count() == 1
    assert page.locator(".tool-body").is_hidden()
    page.click(".tool-toggle")
    assert page.locator(".tool-body").is_visible()
    tool_text = page.inner_text(".msg.tool")
    assert "ls -la /workspace" in tool_text, tool_text
    assert "README.md" in tool_text, tool_text
    assert "exit 0" in page.inner_text(".tool-status")
    assert page.locator(".agent-media img").count() == 1
    assert page.locator(".katex").count() >= 1
    assert page.locator(".katex-display").count() >= 1
    assert "Шрифт" in page.inner_text(".md-font-serif")
    assert "test query" in page.inner_text(".agent-browser-link")
    page.evaluate("document.querySelector('#drawer-close').click()")
    page.wait_for_timeout(380)
    page.screenshot(path=f"{SHOTS}/18-agent-history.png")

    browser.close()

server.shutdown()
print("errors:", json.dumps(errors, ensure_ascii=False, indent=1))
print("shots:", sorted(os.listdir(SHOTS)))
