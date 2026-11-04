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
    perms: saved.perms || { notifications: 'missing', battery: 'optimized' },
    stream: null,
    seq: 1
  };
  function persist() {
    localStorage.setItem('__mock', JSON.stringify({ token: st.token, mode: st.mode, chats: st.chats, perms: st.perms }));
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
  const CONTENT = ['Процесс — ', 'это изолированная ', 'программа со своей ', 'адресной памятью.\n\n', '**Поток** — ', 'единица выполнения ', 'внутри процесса: ', 'он делит память ', 'с остальными ', 'потоками.\n\n', 'Ссылка: https://example.com/', 'a'.repeat(160), '\n\n', '### Заголовок\n', '- пункт один\n', '- пункт два с *курсивом*\n', '\n> цитата\n\n', '[ссылка](https://example.com)\n', '```rust\n', 'fn main() {}\n', '```'];
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
    newChat: () => {
      const c = { id: 'c' + (st.seq++), title: '', created_at: Date.now(), updated_at: Date.now(), messages: [] };
      st.chats.unshift(c); persist(); return ok(c);
    },
    listChats: () => ok(st.chats.map(c => ({ id: c.id, title: c.title || 'Без названия', updated_at: c.updated_at, message_count: c.messages.length }))),
    getChat: id => {
      const c = st.chats.find(x => x.id === id);
      return c ? ok(c) : bad('not_found', 'Чат не найден');
    },
    deleteChat: id => { st.chats = st.chats.filter(x => x.id !== id); persist(); return ok(null); },
    send: function (chatId, text, model, effort, thinking, search, images) {
      if (arguments.length !== 7) throw new Error('send bridge args=' + arguments.length);
      let c = st.chats.find(x => x.id === chatId);
      if (!c) {
        c = { id: 'c' + (st.seq++), title: text.slice(0, 48), created_at: Date.now(), updated_at: Date.now(), messages: [] };
        st.chats.unshift(c);
      }
      c.messages.push({ role: 'user', content: text, ts: Date.now() });
      c.updated_at = Date.now();
      st.stream = { chatId: c.id, ri: 0, ci: 0, reasoning: '', content: '', chat: c, search: !!search };
      persist();
      return ok({ stream_id: 7, chat: c });
    },
    poll: () => {
      const s = st.stream;
      if (!s) return bad('not_found', 'Поток не найден');
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
    appInfo: () => JSON.stringify({ version: '1.6.4', package: 'dev.localapi.deepseek' })
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

    page.click("#search-chip")
    assert "active" in (page.get_attribute("#search-chip", "class") or "")
    page.fill("#input", "Объясни разницу между процессами и потоками")
    page.click("#send-btn")
    page.wait_for_timeout(260)
    page.screenshot(path=f"{SHOTS}/07-chat-streaming.png")
    page.wait_for_timeout(1400)
    assert page.locator(".reasoning").count() == 1
    assert page.locator(".reasoning .reasoning-toggle svg").count() == 1
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
    page.screenshot(path=f"{SHOTS}/09-drawer.png")
    page.click("#drawer-close")

    page.click("#new-chat-btn")
    page.click("#menu-btn")
    page.click("#open-settings")
    page.wait_for_timeout(450)
    page.wait_for_timeout(1900)
    page.screenshot(path=f"{SHOTS}/10-settings.png")

    # Settings is a catalog now: every control lives in its own submenu.
    page.click('.settings-cat[data-settings-pane="model"]')
    page.click('#model-select button[data-model="deepseek-v4-pro"]')
    assert "V4 Pro" in (page.text_content("#sheet-model") or "")
    assert page.is_checked("#search-toggle")
    page.uncheck("#reasoning-toggle")
    assert page.is_hidden("#effort-setting")
    assert page.inner_text("#reasoning-note") == "Выключены"
    page.check("#reasoning-toggle")
    assert page.is_visible("#effort-setting")
    page.click("#settings-back")
    assert "V4 Pro" in page.inner_text("#cat-sub-model")

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
    assert page.is_enabled("#send-btn")

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
    page.click("#menu-btn")
    page.click("#open-settings")
    page.click('.settings-cat[data-settings-pane="model"]')
    assert page.is_hidden("#model-block")
    page.click("#settings-close")
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

    browser.close()

server.shutdown()
print("errors:", json.dumps(errors, ensure_ascii=False, indent=1))
print("shots:", sorted(os.listdir(SHOTS)))
