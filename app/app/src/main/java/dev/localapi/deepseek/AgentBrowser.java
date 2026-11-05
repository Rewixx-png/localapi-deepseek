package dev.localapi.deepseek;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Visible, session-persistent browser controlled by the agent. It is a normal
 * WebView (cookies/localStorage survive between commands) and exposes only a
 * small async command surface to the chat bridge: open/click/type/scroll/
 * extract/screenshot/back/forward/reload/close. Page text and screenshots go
 * back through the agent tool loop; the WebView has no Native JS bridge.
 */
final class AgentBrowser {
    interface Callback {
        void done(JSONObject result);
    }

    private static final int LOAD_TIMEOUT_MS = 25_000;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private WebView web;
    private TextView title;
    private ProgressBar progress;
    private Callback pending;
    private Runnable timeout;
    private String lastAction = "";

    AgentBrowser(Activity activity) {
        this.activity = activity;
    }

    void command(String action, String arg, Callback callback) {
        action = action == null ? "" : action.trim().toLowerCase();
        arg = arg == null ? "" : arg.trim();
        if (action.isEmpty() || callback == null) return;
        if (pending != null) {
            callback.done(error("Браузер занят предыдущей командой"));
            return;
        }
        try {
            show();
        } catch (Exception e) {
            callback.done(error("Не удалось открыть браузер"));
            return;
        }
        lastAction = action;
        switch (action) {
            case "open":
                open(arg, callback);
                break;
            case "close":
                close(callback);
                break;
            case "back":
                navigateBack(callback);
                break;
            case "forward":
                if (web.canGoForward()) loadPending(() -> web.goForward(), callback);
                else callback.done(error("Вперёд истории нет"));
                break;
            case "reload":
                loadPending(() -> web.reload(), callback);
                break;
            case "click":
                click(arg, callback);
                break;
            case "type":
                type(arg, callback);
                break;
            case "scroll":
                scroll(arg, callback);
                break;
            case "extract":
                collect(callback, false);
                break;
            case "screenshot":
                collect(callback, true);
                break;
            case "wait":
                int delay = 800;
                try { delay = Math.max(100, Math.min(5000, Integer.parseInt(arg))); }
                catch (Exception ignored) { }
                handler.postDelayed(() -> collect(callback, false), delay);
                break;
            default:
                callback.done(error("Неизвестное действие браузера: " + action));
        }
    }

    private void show() {
        if (dialog == null) buildDialog();
        if (!dialog.isShowing()) dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void buildDialog() {
        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#111318"));

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackgroundColor(Color.parseColor("#1B1E24"));

        Button back = new Button(activity);
        back.setText("‹");
        back.setTextSize(26);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setTextColor(Color.parseColor("#E2E2E9"));
        back.setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        title = new TextView(activity);
        title.setTextColor(Color.parseColor("#E2E2E9"));
        title.setTextSize(12);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = new Button(activity);
        close.setText("✕");
        close.setTextSize(18);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setTextColor(Color.parseColor("#E2E2E9"));
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        web = new WebView(activity);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUserAgentString(settings.getUserAgentString() + " LocalApiDeepSeek/1.0");
        // Software layer keeps draw(Canvas) reliable for the vision screenshot.
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageFinished(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    failPending("Ошибка загрузки: " + (error == null ? "?" : error.getDescription()));
                }
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onReceivedTitle(WebView view, String pageTitle) {
                if (title != null) title.setText(pageTitle == null ? lastUrl() : pageTitle);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            cancelTimeout();
            if (pending != null) {
                Callback cb = pending;
                pending = null;
                cb.done(error("Браузер закрыт"));
            }
            CookieManager.getInstance().flush();
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP
                    && web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
            return false;
        });
    }

    private void open(String arg, Callback callback) {
        if (arg.isEmpty()) {
            callback.done(error("Не указан адрес"));
            return;
        }
        String url;
        if (arg.matches("(?i)^https?://.*")) {
            url = arg;
        } else if (arg.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            callback.done(error("Разрешены только http/https URL"));
            return;
        } else {
            url = "https://duckduckgo.com/?q=" + android.net.Uri.encode(arg);
        }
        title.setText(url);
        pending = callback;
        pageFinishedReset();
        scheduleTimeout();
        web.loadUrl(url);
    }

    private void navigateBack(Callback callback) {
        if (web.canGoBack()) loadPending(() -> web.goBack(), callback);
        else callback.done(error("Назад истории нет"));
    }

    private void loadPending(Runnable action, Callback callback) {
        pending = callback;
        pageFinishedReset();
        scheduleTimeout();
        action.run();
    }

    private void pageFinishedReset() {
        // Reset happens implicitly with pending; pageFinished() checks it.
    }

    private void pageFinished(String url) {
        if (pending == null) return;
        cancelTimeout();
        handler.postDelayed(() -> {
            if (pending != null) collect(pending, false);
        }, 250);
    }

    private void failPending(String message) {
        if (pending == null) return;
        Callback cb = pending;
        pending = null;
        cancelTimeout();
        cb.done(error(message));
    }

    private void scheduleTimeout() {
        cancelTimeout();
        timeout = () -> {
            if (pending != null) failPending("Таймаут загрузки страницы");
        };
        handler.postDelayed(timeout, LOAD_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (timeout != null) {
            handler.removeCallbacks(timeout);
            timeout = null;
        }
    }

    private void close(Callback callback) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("action", "close");
        } catch (Exception ignored) { }
        callback.done(result);
        if (dialog != null) dialog.dismiss();
    }

    private void click(String selector, Callback callback) {
        if (selector.isEmpty()) {
            callback.done(error("Не указан селектор/текст ссылки"));
            return;
        }
        String js = "(function(s){var el=null;try{el=document.querySelector(s);}catch(e){}"
                + "if(!el){var all=document.querySelectorAll('a,button,input,[role=button]');"
                + "for(var i=0;i<all.length;i++){var tx=(all[i].innerText||all[i].value||"
                + "all[i].getAttribute('aria-label')||'').trim();"
                + "if(tx===s||tx.indexOf(s)>=0){el=all[i];break;}}}"
                + "if(!el)return false;try{el.scrollIntoView({block:'center'});}catch(e){}"
                + "el.click();return true;})(" + JSONObject.quote(selector) + ")";
        web.evaluateJavascript(js, value -> {
            if (!jsTrue(value)) {
                callback.done(error("Элемент не найден: " + selector));
                return;
            }
            handler.postDelayed(() -> collect(callback, false), 900);
        });
    }

    private void type(String arg, Callback callback) {
        int split = arg.indexOf('|');
        if (split <= 0) {
            callback.done(error("Формат: type|CSS-селектор|текст"));
            return;
        }
        String selector = arg.substring(0, split).trim();
        String text = arg.substring(split + 1);
        String js = "(function(s,v){var el=document.querySelector(s);if(!el)return false;"
                + "try{el.focus();}catch(e){}"
                + "if('value' in el){el.value=v;}else{el.textContent=v;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));return true;})"
                + "(" + JSONObject.quote(selector) + "," + JSONObject.quote(text) + ")";
        web.evaluateJavascript(js, value -> {
            if (!jsTrue(value)) {
                callback.done(error("Поле не найдено: " + selector));
                return;
            }
            handler.postDelayed(() -> collect(callback, false), 400);
        });
    }

    private void scroll(String arg, Callback callback) {
        String direction = arg.isEmpty() ? "down" : arg;
        String js = "(function(d){if(d==='top')window.scrollTo(0,0);"
                + "else if(d==='bottom')window.scrollTo(0,document.body.scrollHeight);"
                + "else if(d==='up')window.scrollBy(0,-Math.max(300,innerHeight*0.8));"
                + "else window.scrollBy(0,Math.max(300,innerHeight*0.8));return true;})("
                + JSONObject.quote(direction) + ")";
        web.evaluateJavascript(js, value -> handler.postDelayed(
                () -> collect(callback, false), 350));
    }

    private void collect(Callback callback, boolean withScreenshot) {
        if (callback == null) return;
        pending = null;
        cancelTimeout();
        if (web == null) {
            callback.done(error("Браузер не создан"));
            return;
        }
        String js = "(function(){var t=document.body?document.body.innerText:'';"
                + "var links=[];var as=document.querySelectorAll('a[href]');"
                + "for(var i=0;i<as.length&&links.length<80;i++){var a=as[i];"
                + "var tx=(a.innerText||a.getAttribute('aria-label')||'').trim();"
                + "if(tx)links.push({t:tx.slice(0,140),u:a.href});}"
                + "return JSON.stringify({url:location.href,title:document.title,"
                + "text:t.slice(0,20000),links:links});})()";
        web.evaluateJavascript(js, value -> {
            JSONObject result = new JSONObject();
            try {
                Object decoded = new JSONTokener(value).nextValue();
                if (decoded instanceof String) {
                    JSONObject page = new JSONObject((String) decoded);
                    result.put("ok", true);
                    result.put("action", lastAction);
                    result.put("url", page.optString("url"));
                    result.put("title", page.optString("title"));
                    result.put("text", page.optString("text"));
                    result.put("links", page.optJSONArray("links"));
                    if (withScreenshot) result.put("screenshot", screenshotData());
                } else {
                    result = error("Не удалось прочитать страницу");
                }
            } catch (Exception e) {
                result = error("Не удалось прочитать страницу: " + e.getMessage());
            }
            callback.done(result);
        });
    }

    private String screenshotData() {
        if (web == null || web.getWidth() <= 0 || web.getHeight() <= 0) return "";
        try {
            Bitmap bitmap = Bitmap.createBitmap(web.getWidth(), web.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            web.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, out);
            bitmap.recycle();
            return "data:image/png;base64,"
                    + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean jsTrue(String value) {
        return "true".equals(value) || "\"true\"".equals(value);
    }

    void destroy() {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
            dialog = null;
        }
        if (web != null) {
            web.destroy();
            web = null;
        }
    }

    private JSONObject error(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("error", message);
        } catch (Exception ignored) { }
        return result;
    }

    private String lastUrl() {
        return web == null || web.getUrl() == null ? "" : web.getUrl();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
