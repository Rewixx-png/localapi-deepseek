package dev.localapi.deepseek;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;

/**
 * Visible, session-persistent browser controlled by the agent.
 * Indexes visible interactive elements ([1]..[N]) across DOM and open shadow
 * roots, handles React/Vue controlled inputs via native property setters, and
 * supports click/type/select/press/hover/find/wait_for/scroll/html/screenshot.
 */
final class AgentBrowser {
    interface Callback {
        void done(JSONObject result);
    }

    private static final int LOAD_TIMEOUT_MS = 25_000;
    private static final int MAX_SCREENSHOT_SIDE = 1024;

    private static final String JS_HELPERS =
            "(function(){if(window.__dsAgent)return;window.__dsAgent={};"
            + "function walkRoots(root,fn){fn(root);"
            + "var all=root.querySelectorAll('*');"
            + "for(var i=0;i<all.length;i++){if(all[i].shadowRoot)walkRoots(all[i].shadowRoot,fn);"
            + "if(all[i].tagName==='IFRAME'){try{if(all[i].contentDocument)walkRoots(all[i].contentDocument,fn);}catch(e){}}}}"
            + "function isVisible(el){if(!el||el.nodeType!==1)return false;"
            + "var st=getComputedStyle(el);if(st.display==='none'||st.visibility==='hidden'||parseFloat(st.opacity)===0)return false;"
            + "var r=el.getBoundingClientRect();return r.width>1&&r.height>1;}"
            + "function qAll(sel){var res=[];walkRoots(document,function(rt){try{var nl=rt.querySelectorAll(sel);"
            + "for(var i=0;i<nl.length;i++)res.push(nl[i]);}catch(e){}});return res;}"
            + "function findEl(q){if(!q)return null;q=String(q).trim();"
            + "var m=/^(?:@|#)?(\\d+)$/.exec(q);if(m){var byRef=qAll('[data-ds-ref=\"'+m[1]+'\"]');if(byRef.length)return byRef[0];}"
            + "var direct=qAll(q);for(var i=0;i<direct.length;i++){if(isVisible(direct[i]))return direct[i];}"
            + "if(direct.length)return direct[0];"
            + "var cand=qAll('a,button,input,textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=switch],[contenteditable],[onclick]');"
            + "var ql=q.toLowerCase(),sub=null;"
            + "for(var j=0;j<cand.length;j++){var el=cand[j];if(!isVisible(el))continue;"
            + "var vals=[el.innerText,el.value,el.getAttribute('aria-label'),el.getAttribute('placeholder'),el.getAttribute('title'),el.getAttribute('name')];"
            + "for(var k=0;k<vals.length;k++){var v=(vals[k]||'').trim();if(!v)continue;"
            + "if(v===q||v.toLowerCase()===ql)return el;"
            + "if(!sub&&v.toLowerCase().indexOf(ql)>=0)sub=el;}}return sub;}"
            + "function triggerClick(el){try{el.scrollIntoView({block:'center',inline:'center'});}catch(e){}"
            + "try{el.focus({preventScroll:true});}catch(e){}"
            + "['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){"
            + "try{el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,composed:true,view:window}));}catch(e){}});"
            + "try{el.click();}catch(e){el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,composed:true}));}}"
            + "function setNativeVal(el,val){try{el.scrollIntoView({block:'center'});}catch(e){}"
            + "try{el.focus();}catch(e){}"
            + "if(el.isContentEditable){el.innerText=val;"
            + "el.dispatchEvent(new Event('input',{bubbles:true,composed:true}));"
            + "el.dispatchEvent(new Event('change',{bubbles:true}));return true;}"
            + "var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:(el.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype);"
            + "var desc=Object.getOwnPropertyDescriptor(proto,'value');"
            + "if(desc&&desc.set)desc.set.call(el,val);else el.value=val;"
            + "if(el._valueTracker){try{el._valueTracker.setValue('');}catch(e){}}"
            + "el.dispatchEvent(new Event('input',{bubbles:true,composed:true}));"
            + "el.dispatchEvent(new Event('change',{bubbles:true}));return true;}"
            + "window.__dsAgent={qAll:qAll,findEl:findEl,isVisible:isVisible,triggerClick:triggerClick,setNativeVal:setNativeVal};})();";

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private WebView web;
    private TextView title;
    private TextView subtitle;
    private ProgressBar progress;
    private Callback pending;
    private Runnable timeout;
    private Runnable pendingCollect;
    private String lastAction = "";
    private boolean pageLoading;
    private volatile boolean online = true;

    AgentBrowser(Activity activity) {
        this.activity = activity;
    }

    void setOnline(boolean enabled) {
        this.online = enabled;
        if (!enabled) {
            handler.post(() -> {
                if (web != null) web.stopLoading();
                failPending("Доступ к интернету выключен");
            });
        }
    }

    void command(String action, String arg, Callback callback) {
        action = action == null ? "" : action.trim().toLowerCase();
        arg = arg == null ? "" : arg.trim();
        if (action.isEmpty() || callback == null) return;
        if (!online && !"close".equals(action)) {
            callback.done(error("Доступ к интернету выключен"));
            return;
        }
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
                if (web.canGoBack()) loadPending(() -> web.goBack(), callback);
                else callback.done(error("Назад истории нет"));
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
            case "select":
                selectOption(arg, callback);
                break;
            case "press":
                pressKey(arg, callback);
                break;
            case "hover":
                hover(arg, callback);
                break;
            case "find":
                findText(arg, callback);
                break;
            case "scroll":
                scroll(arg, callback);
                break;
            case "scroll_to":
                scrollTo(arg, callback);
                break;
            case "wait": {
                int delay = 800;
                try { delay = Math.max(100, Math.min(6000, Integer.parseInt(arg))); }
                catch (Exception ignored) { }
                pending = callback;
                scheduleCollect(callback, delay);
                break;
            }
            case "wait_for":
                waitFor(arg, callback);
                break;
            case "html":
                extractHtml(arg, callback);
                break;
            case "extract":
                pending = callback;
                collect(callback, false);
                break;
            case "screenshot":
                pending = callback;
                collect(callback, true);
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

    @SuppressLint("SetJavaScriptEnabled")
    private void buildDialog() {
        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#111318"));

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackgroundColor(Color.parseColor("#1B1E24"));

        bar.addView(navButton("‹", 24, v -> { if (web != null && web.canGoBack()) web.goBack(); }));
        bar.addView(navButton("›", 24, v -> { if (web != null && web.canGoForward()) web.goForward(); }));
        bar.addView(navButton("↻", 18, v -> { if (web != null) web.reload(); }));

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(6), 0, dp(6), 0);

        title = new TextView(activity);
        title.setTextColor(Color.parseColor("#E2E2E9"));
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(title);

        subtitle = new TextView(activity);
        subtitle.setTextColor(Color.parseColor("#8E9099"));
        subtitle.setTextSize(10);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(subtitle);

        bar.addView(titleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(navButton("✕", 18, v -> dialog.dismiss()));
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
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!online) return true;
                String scheme = request != null && request.getUrl() != null
                        ? request.getUrl().getScheme()
                        : null;
                return !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageLoading = true;
                if (subtitle != null && url != null) subtitle.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoading = false;
                if (subtitle != null && url != null) subtitle.setText(url);
                pageFinished(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    pageLoading = false;
                    failPending("Ошибка загрузки: " + (error == null ? "?" : error.getDescription()));
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
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
            cancelTimers();
            if (pending != null) {
                Callback cb = pending;
                pending = null;
                cb.done(error("Браузер закрыт"));
            }
            CookieManager.getInstance().flush();
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP
                    && web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
            return false;
        });
    }

    private Button navButton(String label, float sizeSp, View.OnClickListener listener) {
        Button b = new Button(activity);
        b.setText(label);
        b.setTextSize(sizeSp);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextColor(Color.parseColor("#E2E2E9"));
        b.setPadding(0, 0, 0, 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(42)));
        b.setOnClickListener(listener);
        return b;
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
            url = AppConfig.DEFAULT_SEARCH_URL_PREFIX + android.net.Uri.encode(arg);
        }
        title.setText(url);
        if (subtitle != null) subtitle.setText(url);
        loadPending(() -> web.loadUrl(url), callback);
    }

    private void loadPending(Runnable action, Callback callback) {
        cancelTimers();
        pending = callback;
        scheduleTimeout();
        action.run();
    }

    private void pageFinished(String url) {
        if (pending == null) return;
        if ("about:blank".equals(url)) return;
        cancelTimeout();
        scheduleCollect(pending, 300);
    }

    private void failPending(String message) {
        cancelTimers();
        if (pending == null) return;
        Callback cb = pending;
        pending = null;
        cb.done(error(message));
    }

    private void scheduleCollect(Callback callback, int delayMs) {
        if (pendingCollect != null) {
            handler.removeCallbacks(pendingCollect);
        }
        pendingCollect = () -> {
            pendingCollect = null;
            if (pending == callback) {
                collect(callback, false);
            }
        };
        handler.postDelayed(pendingCollect, delayMs);
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

    private void cancelTimers() {
        cancelTimeout();
        if (pendingCollect != null) {
            handler.removeCallbacks(pendingCollect);
            pendingCollect = null;
        }
    }

    private void close(Callback callback) {
        cancelTimers();
        pending = null;
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
            callback.done(error("Не указан элемент/селектор/текст для клика"));
            return;
        }
        pending = callback;
        final String beforeUrl = lastUrl();
        String js = JS_HELPERS
                + "(function(s){var el=window.__dsAgent.findEl(s);if(!el)return false;"
                + "window.__dsAgent.triggerClick(el);return true;})("
                + JSONObject.quote(selector) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            if (!jsTrue(value)) {
                pending = null;
                callback.done(error("Элемент не найден: " + selector));
                return;
            }
            // Give JS / navigation a moment to kick off; if full page navigation
            // started, wait for onPageFinished instead of reading half-loaded DOM.
            pendingCollect = () -> {
                pendingCollect = null;
                if (pending != callback) return;
                if (pageLoading || (web != null && !beforeUrl.equals(lastUrl()) && web.getProgress() < 100)) {
                    scheduleTimeout();
                } else {
                    scheduleCollect(callback, 400);
                }
            };
            handler.postDelayed(pendingCollect, 450);
        });
    }

    private void type(String arg, Callback callback) {
        int split = arg.indexOf('|');
        if (split <= 0) {
            callback.done(error("Формат: type|элемент_или_CSS|текст"));
            return;
        }
        pending = callback;
        String selector = arg.substring(0, split).trim();
        String text = arg.substring(split + 1);
        String js = JS_HELPERS
                + "(function(s,v){var el=window.__dsAgent.findEl(s);if(!el)return false;"
                + "return window.__dsAgent.setNativeVal(el,v);})("
                + JSONObject.quote(selector) + "," + JSONObject.quote(text) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            if (!jsTrue(value)) {
                pending = null;
                callback.done(error("Поле не найдено: " + selector));
                return;
            }
            scheduleCollect(callback, 350);
        });
    }

    private void selectOption(String arg, Callback callback) {
        int split = arg.indexOf('|');
        if (split <= 0) {
            callback.done(error("Формат: select|элемент_или_CSS|значение"));
            return;
        }
        pending = callback;
        String selector = arg.substring(0, split).trim();
        String target = arg.substring(split + 1).trim();
        String js = JS_HELPERS
                + "(function(s,v){var el=window.__dsAgent.findEl(s);if(!el||el.tagName!=='SELECT')return false;"
                + "var vl=v.toLowerCase(),chosen=null;"
                + "for(var i=0;i<el.options.length;i++){var o=el.options[i];"
                + "if(o.value===v||o.text.trim()===v||o.text.trim().toLowerCase()===vl){chosen=o.value;break;}}"
                + "if(chosen===null){for(var j=0;j<el.options.length;j++){var o2=el.options[j];"
                + "if(o2.text.toLowerCase().indexOf(vl)>=0){chosen=o2.value;break;}}}"
                + "if(chosen===null)return false;"
                + "return window.__dsAgent.setNativeVal(el,chosen);})("
                + JSONObject.quote(selector) + "," + JSONObject.quote(target) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            if (!jsTrue(value)) {
                pending = null;
                callback.done(error("Select или опция не найдены: " + arg));
                return;
            }
            scheduleCollect(callback, 400);
        });
    }

    private void pressKey(String arg, Callback callback) {
        if (arg.isEmpty()) {
            callback.done(error("Укажите клавишу (например Enter, Escape, Tab)"));
            return;
        }
        final String selector;
        final String key;
        int split = arg.indexOf('|');
        if (split > 0) {
            selector = arg.substring(0, split).trim();
            key = arg.substring(split + 1).trim();
        } else {
            selector = "";
            key = arg;
        }
        pending = callback;
        final String beforeUrl = lastUrl();
        String js = JS_HELPERS
                + "(function(s,k){var el=s?window.__dsAgent.findEl(s):(document.activeElement||document.body);"
                + "if(!el)return false;try{el.focus();}catch(e){}"
                + "var code=k==='Enter'?13:(k==='Escape'?27:(k==='Tab'?9:(k==='ArrowDown'?40:(k==='ArrowUp'?38:(k==='Backspace'?8:0)))));"
                + "var opts={key:k,code:k,keyCode:code,which:code,bubbles:true,cancelable:true,composed:true};"
                + "var ok=el.dispatchEvent(new KeyboardEvent('keydown',opts));"
                + "el.dispatchEvent(new KeyboardEvent('keypress',opts));"
                + "el.dispatchEvent(new KeyboardEvent('keyup',opts));"
                + "if(k==='Enter'&&ok&&el.form){try{if(el.form.requestSubmit)el.form.requestSubmit();else el.form.submit();}catch(e){}}"
                + "return true;})(" + JSONObject.quote(selector) + "," + JSONObject.quote(key) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            if (!jsTrue(value)) {
                pending = null;
                callback.done(error("Элемент не найден: " + selector));
                return;
            }
            pendingCollect = () -> {
                pendingCollect = null;
                if (pending != callback) return;
                if (pageLoading || (web != null && !beforeUrl.equals(lastUrl()) && web.getProgress() < 100)) {
                    scheduleTimeout();
                } else {
                    scheduleCollect(callback, 450);
                }
            };
            handler.postDelayed(pendingCollect, 450);
        });
    }

    private void hover(String selector, Callback callback) {
        if (selector.isEmpty()) {
            callback.done(error("Не указан элемент для hover"));
            return;
        }
        pending = callback;
        String js = JS_HELPERS
                + "(function(s){var el=window.__dsAgent.findEl(s);if(!el)return false;"
                + "try{el.scrollIntoView({block:'center'});}catch(e){}"
                + "['pointerover','pointerenter','mouseover','mouseenter','mousemove'].forEach(function(t){"
                + "try{el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,composed:true,view:window}));}catch(e){}});"
                + "return true;})(" + JSONObject.quote(selector) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            if (!jsTrue(value)) {
                pending = null;
                callback.done(error("Элемент не найден: " + selector));
                return;
            }
            scheduleCollect(callback, 450);
        });
    }

    private void findText(String query, Callback callback) {
        if (query.isEmpty()) {
            callback.done(error("Укажите текст для поиска на странице"));
            return;
        }
        pending = callback;
        String js = JS_HELPERS
                + "(function(q){var ql=q.toLowerCase();var w=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_TEXT);"
                + "var node;while((node=w.nextNode())){var txt=(node.nodeValue||'');var idx=txt.toLowerCase().indexOf(ql);"
                + "if(idx>=0&&node.parentElement&&window.__dsAgent.isVisible(node.parentElement)){"
                + "try{node.parentElement.scrollIntoView({block:'center'});}catch(e){}"
                + "return true;}}return false;})(" + JSONObject.quote(query) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            if (!jsTrue(value)) {
                pending = null;
                callback.done(error("Текст не найден на странице: " + query));
                return;
            }
            scheduleCollect(callback, 300);
        });
    }

    private void scroll(String arg, Callback callback) {
        pending = callback;
        String direction = arg.isEmpty() ? "down" : arg;
        String js = "(function(d){"
                + "var amt=parseInt(d,10);"
                + "var before=window.scrollY;"
                + "if(!isNaN(amt)){window.scrollBy(0,amt);}"
                + "else if(d==='top'){window.scrollTo(0,0);}"
                + "else if(d==='bottom'){window.scrollTo(0,document.body.scrollHeight);}"
                + "else if(d==='up'){window.scrollBy(0,-Math.max(300,innerHeight*0.8));}"
                + "else{window.scrollBy(0,Math.max(300,innerHeight*0.8));}"
                + "if(window.scrollY===before&&d!=='top'&&d!=='bottom'){"
                + "var step=!isNaN(amt)?amt:(d==='up'?-Math.max(300,innerHeight*0.75):Math.max(300,innerHeight*0.75));"
                + "var divs=document.querySelectorAll('main,section,div,[role=main]');"
                + "for(var i=0;i<divs.length;i++){var el=divs[i];"
                + "if(el.scrollHeight>el.clientHeight+40&&getComputedStyle(el).overflowY!=='hidden'){"
                + "var prev=el.scrollTop;el.scrollTop+=step;if(el.scrollTop!==prev)break;}}}"
                + "return true;})(" + JSONObject.quote(direction) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            scheduleCollect(callback, 350);
        });
    }

    private void scrollTo(String selector, Callback callback) {
        if (selector.isEmpty()) {
            callback.done(error("Не указан элемент для scroll_to"));
            return;
        }
        pending = callback;
        String js = JS_HELPERS
                + "(function(s){var el=window.__dsAgent.findEl(s);if(!el)return false;"
                + "try{el.scrollIntoView({block:'center'});}catch(e){}return true;})("
                + JSONObject.quote(selector) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            if (!jsTrue(value)) {
                pending = null;
                callback.done(error("Элемент не найден: " + selector));
                return;
            }
            scheduleCollect(callback, 300);
        });
    }

    private void waitFor(String selector, Callback callback) {
        if (selector.isEmpty()) {
            callback.done(error("Укажите селектор или текст для wait_for"));
            return;
        }
        pending = callback;
        pollWaitFor(selector, callback, 40);
    }

    private void pollWaitFor(String selector, Callback callback, int attemptsLeft) {
        if (pending != callback || web == null) return;
        String js = JS_HELPERS
                + "(function(s){var el=window.__dsAgent.findEl(s);return !!(el&&window.__dsAgent.isVisible(el));})("
                + JSONObject.quote(selector) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            if (jsTrue(value)) {
                scheduleCollect(callback, 200);
            } else if (attemptsLeft <= 1) {
                pending = null;
                callback.done(error("Элемент так и не появился: " + selector));
            } else {
                pendingCollect = () -> {
                    pendingCollect = null;
                    pollWaitFor(selector, callback, attemptsLeft - 1);
                };
                handler.postDelayed(pendingCollect, 250);
            }
        });
    }

    private void extractHtml(String selector, Callback callback) {
        pending = callback;
        String js = JS_HELPERS
                + "(function(s){var el=s?window.__dsAgent.findEl(s):document.body;"
                + "if(!el)return '';return (el.outerHTML||'').slice(0,10000);})("
                + JSONObject.quote(selector) + ")";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            pending = null;
            try {
                Object decoded = new JSONTokener(value).nextValue();
                String html = decoded instanceof String ? (String) decoded : "";
                if (html.isEmpty()) {
                    callback.done(error("Контейнер не найден: " + selector));
                } else {
                    JSONObject result = new JSONObject();
                    result.put("ok", true);
                    result.put("action", "html");
                    result.put("url", lastUrl());
                    result.put("title", title != null ? title.getText().toString() : "");
                    result.put("text", html);
                    callback.done(result);
                }
            } catch (Exception e) {
                callback.done(error("Не удалось прочитать HTML: " + e.getMessage()));
            }
        });
    }

    private void collect(Callback callback, boolean withScreenshot) {
        if (callback == null) return;
        cancelTimers();
        if (pending != callback) return;
        if (web == null) {
            pending = null;
            callback.done(error("Браузер не создан"));
            return;
        }
        String js = JS_HELPERS
                + "(function(){"
                + "var t=(document.body?document.body.innerText:'').slice(0,18000);"
                + "var els=window.__dsAgent.qAll('a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=switch],[role=combobox],[contenteditable],[onclick]');"
                + "var out=[],ref=1;"
                + "for(var i=0;i<els.length&&out.length<120;i++){"
                + "var el=els[i];if(!window.__dsAgent.isVisible(el))continue;"
                + "if(el.disabled||el.getAttribute('aria-disabled')==='true')continue;"
                + "el.setAttribute('data-ds-ref',String(ref));"
                + "var tag=el.tagName.toLowerCase();"
                + "var item={ref:ref,tag:tag};"
                + "if(el.type)item.type=String(el.type).toLowerCase();"
                + "if(el.name)item.name=el.name.slice(0,40);"
                + "if(el.id)item.id=el.id.slice(0,40);"
                + "var lbl=(el.innerText||el.getAttribute('aria-label')||el.getAttribute('title')||'').replace(/\\s+/g,' ').trim();"
                + "if(lbl)item.text=lbl.slice(0,100);"
                + "if(el.placeholder)item.placeholder=el.placeholder.slice(0,60);"
                + "if((tag==='input'||tag==='textarea'||tag==='select')&&el.value!==undefined&&el.type!=='password'){item.value=String(el.value).slice(0,80);}"
                + "if(tag==='input'&&(item.type==='checkbox'||item.type==='radio')){item.checked=!!el.checked;}"
                + "if(tag==='a'&&el.href){item.href=el.href.slice(0,180);}"
                + "if(tag==='select'){var opts=[];for(var j=0;j<el.options.length&&j<12;j++){opts.push((el.options[j].text||'').trim().slice(0,30));}item.options=opts;}"
                + "out.push(item);ref++;}"
                + "return JSON.stringify({url:location.href,title:document.title,scrollY:Math.round(window.scrollY),scrollH:Math.round((document.body||{}).scrollHeight||0),viewH:window.innerHeight,text:t,elements:out});"
                + "})()";
        web.evaluateJavascript(js, value -> {
            if (pending != callback) return;
            pending = null;
            JSONObject result = new JSONObject();
            try {
                Object decoded = new JSONTokener(value).nextValue();
                if (decoded instanceof String) {
                    JSONObject page = new JSONObject((String) decoded);
                    result.put("ok", true);
                    result.put("action", lastAction);
                    result.put("url", page.optString("url"));
                    result.put("title", page.optString("title"));
                    result.put("scrollY", page.optInt("scrollY"));
                    result.put("scrollH", page.optInt("scrollH"));
                    result.put("viewH", page.optInt("viewH"));
                    result.put("text", page.optString("text"));
                    result.put("elements", page.optJSONArray("elements"));
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
        if (web == null) return "";
        int w = web.getWidth();
        int h = web.getHeight();
        if (w <= 0 || h <= 0) return "";
        Bitmap bitmap = null;
        try {
            int max = Math.max(w, h);
            float scale = max > MAX_SCREENSHOT_SIDE ? (float) MAX_SCREENSHOT_SIDE / max : 1f;
            int outW = Math.max(1, Math.round(w * scale));
            int outH = Math.max(1, Math.round(h * scale));
            bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            if (scale < 1f) canvas.scale(scale, scale);
            web.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
            return "data:image/png;base64,"
                    + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private boolean jsTrue(String value) {
        return "true".equals(value) || "\"true\"".equals(value);
    }

    void destroy() {
        cancelTimers();
        handler.removeCallbacksAndMessages(null);
        if (pending != null) {
            Callback cb = pending;
            pending = null;
            cb.done(error("Браузер закрыт"));
        }
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
