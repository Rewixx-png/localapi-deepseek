package dev.localapi.deepseek.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

private val katexSizeCache = object : LinkedHashMap<String, Pair<Float, Float>>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Float, Float>>?): Boolean = size > 128
}

private val KATEX_SHELL_HTML = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="katex/katex.min.css">
<style>
  * { box-sizing: border-box; }
  html, body {
    margin: 0;
    padding: 0;
    background: transparent;
    font-family: Roboto, system-ui, sans-serif;
    line-height: 1.48;
    -webkit-text-size-adjust: 100%;
    text-size-adjust: 100%;
    overflow: hidden;
  }
  #root.display-mode {
    display: inline-block;
    width: max-content;
    padding: 6px 8px;
    white-space: nowrap;
    text-align: center;
  }
  #root.display-mode .katex-display {
    display: inline-block;
    margin: 0 !important;
    padding: 4px 0;
    overflow: visible !important;
  }
  #root.display-mode .katex-display > .katex {
    display: inline-block;
    white-space: nowrap;
  }
  #root.inline-mode {
    display: flow-root;
    width: 100%;
    padding: 2px 0 4px 0;
    word-wrap: break-word;
    overflow-wrap: anywhere;
  }
  #root.inline-mode .katex-display {
    margin: 6px 0 !important;
    padding: 4px 0;
    overflow-x: auto;
    overflow-y: visible;
  }
  code {
    font-family: ui-monospace, "Roboto Mono", monospace;
    font-size: 0.9em;
    font-weight: 500;
  }
  a { text-decoration: underline; }
  del { opacity: 0.8; }
  strong { font-weight: 600; }
  .katex { color: inherit; font-size: 1.04em; }
  .md-font-serif { font-family: Georgia, "Noto Serif", serif; }
  .md-font-sans { font-family: Roboto, "Noto Sans", sans-serif; }
  .md-font-mono { font-family: "Roboto Mono", monospace; }
  .md-font-cursive { font-family: cursive; }
  .md-font-condensed { font-family: sans-serif-condensed, sans-serif; }
  .md-font-light { font-weight: 300; }
  .md-font-black { font-weight: 900; }
  .md-font-smallcaps { font-variant: small-caps; letter-spacing: 0.04em; }
</style>
<style id="dyn-style"></style>
</head>
<body>
<div id="root"></div>
<div id="font-warmup" style="position:fixed;left:-9999px;top:-9999px;opacity:0;pointer-events:none;font-size:16px"></div>
<script src="katex/katex.min.js"></script>
<script src="katex/contrib/auto-render.min.js"></script>
<script>
  (function () {
    var root = document.getElementById("root");
    var dynStyle = document.getElementById("dyn-style");
    var renderSeq = 0;
    var lastSentW = 0;
    var lastSentH = 0;
    var currentIsDisplay = false;

    try {
      var warmup = document.getElementById("font-warmup");
      if (warmup && window.katex) {
        warmup.innerHTML = window.katex.renderToString("\\int_0^1 \\frac{\\sum \\mathbb{C} \\mathbf{A}}{\\sqrt[3]{x_n^2}} \\, dx", { throwOnError: false, displayMode: true });
      }
    } catch (e) {}

    function measureRoot() {
      var rect = root.getBoundingClientRect();
      var minTop = rect.top;
      var maxBottom = rect.bottom;
      var minLeft = rect.left;
      var maxRight = rect.right;
      var els = root.querySelectorAll(".katex, .katex-html, .katex-display");
      for (var i = 0; i < els.length; i++) {
        var r = els[i].getBoundingClientRect();
        if (r.height > 0 && r.width > 0) {
          if (r.top < minTop) minTop = r.top;
          if (r.bottom > maxBottom) maxBottom = r.bottom;
          if (r.left < minLeft) minLeft = r.left;
          if (r.right > maxRight) maxRight = r.right;
        }
      }
      var cssH = Math.ceil(Math.max(rect.height, root.scrollHeight, maxBottom - minTop));
      var cssW = currentIsDisplay
        ? Math.ceil(Math.max(rect.width, maxRight - minLeft))
        : Math.ceil(rect.width);
      var dpr = window.devicePixelRatio || 1;
      return {
        physW: Math.ceil(cssW * dpr),
        physH: Math.ceil(cssH * dpr)
      };
    }

    function applyConfigAndMath(bodyHtml, cfgJson) {
      var cfg = {};
      try { cfg = JSON.parse(cfgJson); } catch (e) {}
      currentIsDisplay = !!cfg.isDisplay;

      document.documentElement.style.width = currentIsDisplay ? "max-content" : "100%";
      document.body.style.width = currentIsDisplay ? "max-content" : "100%";
      document.body.style.color = cfg.color || "#e2e2e9";
      document.body.style.fontSize = (cfg.fontSize || 15) + "px";
      document.body.style.fontWeight = cfg.fontWeight || "400";
      if (dynStyle) {
        dynStyle.textContent = "code, a { color: " + (cfg.accent || "#aac7ff") + "; }";
      }

      root.className = currentIsDisplay ? "display-mode" : "inline-mode";
      root.style.textAlign = currentIsDisplay ? "center" : (cfg.align || "left");
      root.innerHTML = bodyHtml || "";

      try {
        if (window.renderMathInElement) {
          window.renderMathInElement(root, {
            delimiters: [
              { left: "$$", right: "$$", display: true },
              { left: "\\[", right: "\\]", display: true },
              { left: "\\(", right: "\\)", display: false },
              { left: "$", right: "$", display: false }
            ],
            ignoredTags: ["script", "noscript", "style", "textarea", "pre", "code"],
            throwOnError: false,
            strict: false,
            errorColor: "#ffb4ab"
          });
        }
      } catch (e) {}
    }

    function measureAndReport(seq) {
      if (seq !== renderSeq) return;
      try {
        var m = measureRoot();
        if (m.physH > 0 && m.physW > 0 && (m.physW !== lastSentW || m.physH !== lastSentH)) {
          lastSentW = m.physW;
          lastSentH = m.physH;
          if (window.KatexBridge) {
            window.KatexBridge.setSize(m.physW, m.physH);
          }
        }
      } catch (e) {}
    }

    if (window.ResizeObserver) {
      try {
        new ResizeObserver(function () {
          if (window.KatexBridge) measureAndReport(renderSeq);
        }).observe(root);
      } catch (e) {}
    }

    window.__renderLatex = function (bodyHtml, cfgJson) {
      renderSeq++;
      var seq = renderSeq;
      lastSentW = 0;
      lastSentH = 0;
      applyConfigAndMath(bodyHtml, cfgJson);
      measureAndReport(seq);
      if (window.requestAnimationFrame) {
        requestAnimationFrame(function () { measureAndReport(seq); });
      }
      if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(function () { measureAndReport(seq); });
      }
      setTimeout(function () { measureAndReport(seq); }, 45);
      setTimeout(function () { measureAndReport(seq); }, 180);
    };

    window.__renderBitmap = function (jobId, bodyHtml, cfgJson) {
      renderSeq++;
      var seq = renderSeq;
      applyConfigAndMath(bodyHtml, cfgJson);

      var finish = function () {
        if (seq !== renderSeq) return;
        requestAnimationFrame(function () {
          if (seq !== renderSeq) return;
          try {
            var m = measureRoot();
            if (window.KatexBmpBridge) {
              window.KatexBmpBridge.onReady(jobId, m.physW, m.physH);
            }
          } catch (e) {
            if (window.KatexBmpBridge) window.KatexBmpBridge.onReady(jobId, 0, 0);
          }
        });
      };

      if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(finish);
      } else {
        finish();
      }
    };
  })();
</script>
</body>
</html>
""".trimIndent()

data class CachedKatexBmp(
    val image: ImageBitmap,
    val widthPx: Int,
    val heightPx: Int,
    val byteCount: Int
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

object KatexBitmapRenderer {
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024
    private const val MAX_BMP_DIM = 2400
    private const val JOB_TIMEOUT_MS = 1800L

    private class RenderJob(
        val renderKey: String,
        val htmlBody: String,
        val cfgJson: String,
        val availWidthPx: Int
    ) {
        var seq: Long = 0L
        val listeners = LinkedHashSet<(CachedKatexBmp?) -> Unit>()
    }

    private class BmpBridge {
        @JavascriptInterface
        fun onReady(jobId: Long, physW: Int, physH: Int) {
            mainHandler.post { handleMeasured(jobId, physW, physH) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, CachedKatexBmp>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: CachedKatexBmp): Int = value.byteCount
    }
    private val failedKeys = HashSet<String>()
    private val pendingJobs = LinkedHashMap<String, RenderJob>()
    private val lifoOrder = ArrayList<String>()

    private var hostRef: WeakReference<Activity>? = null
    private var webView: WebView? = null
    private var shellLoaded = false
    private var activeJob: RenderJob? = null
    private var jobSeq = 0L
    private var layoutListener: View.OnLayoutChangeListener? = null
    private var timeoutRunnable: Runnable? = null

    fun getCached(renderKey: String): CachedKatexBmp? = cache.get(renderKey)
    fun isFailed(renderKey: String): Boolean = failedKeys.contains(renderKey)

    fun warmup(context: Context) {
        val act = context.findActivity() ?: return
        ensureWebView(act)
    }

    fun request(
        context: Context,
        renderKey: String,
        htmlBody: String,
        cfgJson: String,
        availWidthPx: Int,
        listener: (CachedKatexBmp?) -> Unit
    ): () -> Unit {
        cache.get(renderKey)?.let {
            listener(it)
            return {}
        }
        if (failedKeys.contains(renderKey)) {
            listener(null)
            return {}
        }
        val act = context.findActivity()
        if (act == null || act.isFinishing || act.isDestroyed) {
            listener(null)
            return {}
        }
        ensureWebView(act)

        val current = activeJob
        if (current != null && current.renderKey == renderKey) {
            current.listeners.add(listener)
            return { current.listeners.remove(listener) }
        }

        val job = pendingJobs.getOrPut(renderKey) {
            RenderJob(renderKey, htmlBody, cfgJson, availWidthPx)
        }
        job.listeners.add(listener)
        lifoOrder.remove(renderKey)
        lifoOrder.add(renderKey)

        if (activeJob == null && shellLoaded) {
            startNext()
        }
        return {
            job.listeners.remove(listener)
            if (job.listeners.isEmpty() && activeJob !== job) {
                pendingJobs.remove(renderKey)
                lifoOrder.remove(renderKey)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(activity: Activity) {
        val existingHost = hostRef?.get()
        if (webView != null && existingHost === activity) return
        destroyWebViewOnly()
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        hostRef = WeakReference(activity)
        shellLoaded = false

        val web = WebView(activity).apply {
            alpha = 0.01f
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.setSupportZoom(false)
            settings.textZoom = 100
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == "about:blank" || view !== webView) return
                    shellLoaded = true
                    if (activeJob == null) startNext()
                }
            }
            addJavascriptInterface(BmpBridge(), "KatexBmpBridge")
        }
        contentRoot.addView(web, 0, FrameLayout.LayoutParams(900, 160))
        webView = web
        web.loadDataWithBaseURL("file:///android_asset/web/", KATEX_SHELL_HTML, "text/html", "utf-8", null)
    }

    private fun startNext() {
        val web = webView ?: return
        if (!shellLoaded || activeJob != null) return
        val act = hostRef?.get()
        if (act == null || act.isFinishing || act.isDestroyed) {
            destroy()
            return
        }

        var next: RenderJob? = null
        while (lifoOrder.isNotEmpty()) {
            val key = lifoOrder.removeAt(lifoOrder.lastIndex)
            val candidate = pendingJobs.remove(key) ?: continue
            if (candidate.listeners.isNotEmpty()) {
                next = candidate
                break
            }
        }
        val job = next ?: return
        val seq = ++jobSeq
        job.seq = seq
        activeJob = job

        scheduleTimeout(seq)

        val initW = job.availWidthPx.coerceIn(64, MAX_BMP_DIM)
        val initH = 120
        val runJs = {
            if (activeJob === job && job.seq == seq && webView === web) {
                val js = "window.__renderBitmap && window.__renderBitmap($seq, ${JSONObject.quote(job.htmlBody)}, ${JSONObject.quote(job.cfgJson)});"
                web.evaluateJavascript(js, null)
            }
        }
        if (web.width == initW && web.height == initH) {
            runJs()
        } else {
            resizeAndThen(web, initW, initH, seq, runJs)
        }
    }

    private fun handleMeasured(seq: Long, physW: Int, physH: Int) {
        val job = activeJob ?: return
        val web = webView ?: return
        if (job.seq != seq) return

        if (physW <= 0 || physH <= 0 || physW > MAX_BMP_DIM || physH > MAX_BMP_DIM) {
            finishJob(job, null, markFailed = true)
            return
        }

        val capture = {
            if (activeJob === job && job.seq == seq && webView === web) {
                web.scrollTo(0, 0)
                web.postVisualStateCallback(seq, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (activeJob !== job || job.seq != seq || webView !== web) return
                        try {
                            val bmp = Bitmap.createBitmap(physW, physH, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bmp)
                            web.draw(canvas)
                            val cached = CachedKatexBmp(
                                image = bmp.asImageBitmap(),
                                widthPx = physW,
                                heightPx = physH,
                                byteCount = bmp.allocationByteCount
                            )
                            cache.put(job.renderKey, cached)
                            finishJob(job, cached, markFailed = false)
                        } catch (e: Throwable) {
                            finishJob(job, null, markFailed = true)
                        }
                    }
                })
            }
        }

        if (web.width == physW && web.height == physH) {
            capture()
        } else {
            resizeAndThen(web, physW, physH, seq, capture)
        }
    }

    private fun resizeAndThen(
        web: WebView,
        w: Int,
        h: Int,
        seq: Long,
        then: () -> Unit
    ) {
        clearLayoutListener(web)
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                if ((right - left) == w && (bottom - top) == h) {
                    web.removeOnLayoutChangeListener(this)
                    if (layoutListener === this) layoutListener = null
                    if (activeJob?.seq == seq) mainHandler.post(then)
                }
            }
        }
        layoutListener = listener
        web.addOnLayoutChangeListener(listener)
        web.layoutParams = FrameLayout.LayoutParams(w, h)
        web.requestLayout()
    }

    private fun clearLayoutListener(web: WebView) {
        layoutListener?.let {
            web.removeOnLayoutChangeListener(it)
            layoutListener = null
        }
    }

    private fun scheduleTimeout(seq: Long) {
        cancelTimeout()
        val r = Runnable {
            val job = activeJob
            if (job != null && job.seq == seq) {
                finishJob(job, null, markFailed = true)
            }
        }
        timeoutRunnable = r
        mainHandler.postDelayed(r, JOB_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun finishJob(job: RenderJob, result: CachedKatexBmp?, markFailed: Boolean) {
        cancelTimeout()
        webView?.let { clearLayoutListener(it) }
        if (markFailed) failedKeys.add(job.renderKey)
        activeJob = null
        val cbs = job.listeners.toList()
        job.listeners.clear()
        cbs.forEach { it(result) }
        startNext()
    }

    private fun destroyWebViewOnly() {
        cancelTimeout()
        val web = webView
        webView = null
        shellLoaded = false
        activeJob = null
        if (web != null) {
            clearLayoutListener(web)
            web.removeJavascriptInterface("KatexBmpBridge")
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }

    fun destroy() {
        pendingJobs.clear()
        lifoOrder.clear()
        failedKeys.clear()
        cache.evictAll()
        destroyWebViewOnly()
        hostRef = null
    }
}

private class KatexBridgeHost {
    @Volatile var onSize: ((Float, Float) -> Unit)? = null

    @JavascriptInterface
    fun setSize(physW: Float, physH: Float) {
        onSize?.invoke(physW, physH)
    }
}

private class KatexWebView(context: Context) : WebView(context) {
    val bridge = KatexBridgeHost()
    @Volatile var shellLoaded: Boolean = false
    @Volatile var lastRenderKey: String? = null
    @Volatile var pendingJs: String? = null
    @Volatile var allowTouch: Boolean = false
    @Volatile var openUrlHandler: ((String) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (!allowTouch) return false
        return super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (!allowTouch) return false
        return super.onInterceptTouchEvent(ev)
    }
}

@Composable
internal fun KatexRichBlock(
    htmlBody: String,
    textScale: Float,
    isDisplay: Boolean,
    availWidthDp: Dp? = null,
    baseFontSp: Float = 15.5f,
    textAlign: TextAlign = TextAlign.Start,
    isBold: Boolean = false,
    textColorOverride: Color? = null,
    allowTouchOverride: Boolean? = null,
    onOpenUrl: (String) -> Unit
) {
    if (availWidthDp != null) {
        KatexRichBlockInner(
            htmlBody = htmlBody,
            textScale = textScale,
            isDisplay = isDisplay,
            availWidthDp = availWidthDp,
            baseFontSp = baseFontSp,
            textAlign = textAlign,
            isBold = isBold,
            textColorOverride = textColorOverride,
            allowTouchOverride = allowTouchOverride,
            onOpenUrl = onOpenUrl
        )
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            KatexRichBlockInner(
                htmlBody = htmlBody,
                textScale = textScale,
                isDisplay = isDisplay,
                availWidthDp = maxWidth,
                baseFontSp = baseFontSp,
                textAlign = textAlign,
                isBold = isBold,
                textColorOverride = textColorOverride,
                allowTouchOverride = allowTouchOverride,
                onOpenUrl = onOpenUrl
            )
        }
    }
}

@Composable
private fun KatexRichBlockInner(
    htmlBody: String,
    textScale: Float,
    isDisplay: Boolean,
    availWidthDp: Dp,
    baseFontSp: Float,
    textAlign: TextAlign,
    isBold: Boolean,
    textColorOverride: Color?,
    allowTouchOverride: Boolean?,
    onOpenUrl: (String) -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val uiScale = LocalUiScale.current

    val textColorHex = colorToHex(textColorOverride ?: cs.onSurface)
    val accentHex = colorToHex(cs.primary)
    val fontSizeCssPx = (baseFontSp * textScale * uiScale * density.fontScale).coerceAtLeast(6f)
    val cssTextAlign = when (textAlign) {
        TextAlign.Center -> "center"
        TextAlign.End, TextAlign.Right -> "right"
        else -> "left"
    }
    val cssFontWeight = if (isBold) "600" else "400"
    val availWidthVal = availWidthDp.value.coerceAtLeast(40f)
    val availWidthPx = (availWidthVal * density.density).roundToInt().coerceAtLeast(48)
    val widthBucket = if (isDisplay) 0 else (availWidthPx / 8)
    val scaleBucket = (fontSizeCssPx * 10f).roundToInt()

    val cacheKey = remember(htmlBody, scaleBucket, widthBucket, isDisplay, cssTextAlign, cssFontWeight) {
        "${htmlBody.hashCode()}:${htmlBody.length}:$scaleBucket:$widthBucket:$isDisplay:$cssTextAlign:$cssFontWeight"
    }
    val renderKey = remember(cacheKey, textColorHex, accentHex) {
        "$cacheKey:$textColorHex:$accentHex"
    }
    val cfgJson = remember(textColorHex, accentHex, fontSizeCssPx, isDisplay, cssTextAlign, cssFontWeight) {
        JSONObject()
            .put("color", textColorHex)
            .put("accent", accentHex)
            .put("fontSize", fontSizeCssPx.toDouble())
            .put("isDisplay", isDisplay)
            .put("align", cssTextAlign)
            .put("fontWeight", cssFontWeight)
            .toString()
    }

    val hasLinks = remember(htmlBody) { htmlBody.contains("<a href=") }
    var useLiveFallback by remember(renderKey) {
        mutableStateOf(hasLinks || KatexBitmapRenderer.isFailed(renderKey))
    }

    if (useLiveFallback) {
        KatexLiveWebView(
            htmlBody = htmlBody,
            cfgJson = cfgJson,
            cacheKey = cacheKey,
            renderKey = renderKey,
            textScale = textScale,
            isDisplay = isDisplay,
            availWidthVal = availWidthVal,
            allowTouch = allowTouchOverride ?: hasLinks,
            onOpenUrl = onOpenUrl
        )
        return
    }

    var cachedBmp by remember(renderKey) {
        mutableStateOf(KatexBitmapRenderer.getCached(renderKey))
    }

    DisposableEffect(renderKey) {
        val current = KatexBitmapRenderer.getCached(renderKey)
        if (current != null) {
            cachedBmp = current
            onDispose { }
        } else {
            val cancel = KatexBitmapRenderer.request(
                context = context,
                renderKey = renderKey,
                htmlBody = htmlBody,
                cfgJson = cfgJson,
                availWidthPx = availWidthPx
            ) { result ->
                if (result != null) {
                    cachedBmp = result
                } else {
                    useLiveFallback = true
                }
            }
            onDispose { cancel() }
        }
    }

    val bmp = cachedBmp
    val cachedDim = katexSizeCache[cacheKey]
    val targetW = when {
        bmp != null && isDisplay -> (bmp.widthPx / density.density)
        isDisplay -> cachedDim?.first ?: availWidthVal.coerceAtMost(240f)
        else -> availWidthVal
    }
    val targetH = when {
        bmp != null -> (bmp.heightPx / density.density)
        else -> cachedDim?.second ?: ((if (isDisplay) 44f else 24f) * textScale).coerceAtLeast(20f)
    }
    val alphaAnim by animateFloatAsState(
        targetValue = if (bmp != null) 1f else 0.15f,
        animationSpec = tween(durationMillis = 160),
        label = "katex_bmp_alpha"
    )

    if (bmp != null) {
        Image(
            bitmap = bmp.image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .width(targetW.dp)
                .height(targetH.dp)
                .graphicsLayer { alpha = alphaAnim }
        )
    } else {
        Box(
            modifier = Modifier
                .width(targetW.dp)
                .height(targetH.dp)
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun KatexLiveWebView(
    htmlBody: String,
    cfgJson: String,
    cacheKey: String,
    renderKey: String,
    textScale: Float,
    isDisplay: Boolean,
    availWidthVal: Float,
    allowTouch: Boolean,
    onOpenUrl: (String) -> Unit
) {
    val density = LocalDensity.current
    val cached = katexSizeCache[cacheKey]
    val initialHeight = cached?.second ?: ((if (isDisplay) 44f else 24f) * textScale).coerceAtLeast(22f)
    val initialWidth = cached?.first ?: (if (isDisplay) availWidthVal.coerceAtMost(240f) else availWidthVal)

    var heightDp by remember(cacheKey) { mutableFloatStateOf(initialHeight) }
    var widthDp by remember(cacheKey) { mutableFloatStateOf(initialWidth) }
    var measured by remember(cacheKey) { mutableStateOf(cached != null) }
    val alphaAnim by animateFloatAsState(
        targetValue = if (measured) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "katex_live_alpha"
    )

    val boxModifier = if (isDisplay) {
        Modifier
            .width(widthDp.coerceIn(32f, 2400f).dp)
            .height(heightDp.dp)
            .graphicsLayer { alpha = alphaAnim }
    } else {
        Modifier
            .width(availWidthVal.dp)
            .height(heightDp.dp)
            .graphicsLayer { alpha = alphaAnim }
    }

    AndroidView(
        factory = { ctx ->
            KatexWebView(ctx).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setSupportZoom(false)
                settings.textZoom = 100
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return true
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            openUrlHandler?.invoke(url)
                        }
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url == "about:blank") return
                        shellLoaded = true
                        val js = pendingJs
                        if (js != null) {
                            pendingJs = null
                            evaluateJavascript(js, null)
                        }
                    }
                }
                addJavascriptInterface(bridge, "KatexBridge")
                loadDataWithBaseURL("file:///android_asset/web/", KATEX_SHELL_HTML, "text/html", "utf-8", null)
            }
        },
        onReset = { web ->
            web.bridge.onSize = null
            web.openUrlHandler = null
            web.lastRenderKey = null
            web.pendingJs = null
            web.allowTouch = false
        },
        onRelease = { web ->
            web.bridge.onSize = null
            web.openUrlHandler = null
            web.pendingJs = null
            web.removeJavascriptInterface("KatexBridge")
            web.destroy()
        },
        update = { web ->
            web.allowTouch = allowTouch
            web.openUrlHandler = onOpenUrl
            val expectedKey = renderKey
            web.bridge.onSize = { physW, physH ->
                web.post {
                    if (web.lastRenderKey != expectedKey) return@post
                    if (physH > 4f && physW > 4f) {
                        val nextH = (physH / density.density).coerceAtLeast(12f)
                        val nextW = if (isDisplay) {
                            (physW / density.density).coerceIn(32f, 2400f)
                        } else {
                            availWidthVal
                        }
                        val hChanged = abs(nextH - heightDp) > 1.0f
                        val wChanged = isDisplay && abs(nextW - widthDp) > 1.0f
                        if (hChanged) heightDp = nextH
                        if (wChanged) widthDp = nextW
                        if (hChanged || wChanged || !measured) {
                            measured = true
                            katexSizeCache[cacheKey] = widthDp to heightDp
                        }
                    }
                }
            }
            if (web.lastRenderKey != expectedKey) {
                web.lastRenderKey = expectedKey
                val js = "window.__renderLatex && window.__renderLatex(${JSONObject.quote(htmlBody)}, ${JSONObject.quote(cfgJson)});"
                if (web.shellLoaded) {
                    web.evaluateJavascript(js, null)
                } else {
                    web.pendingJs = js
                }
            }
        },
        modifier = boxModifier
    )
}

private fun colorToHex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())
