package dev.localapi.deepseek

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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val bullet: String, val text: String) : MdBlock
    data object Rule : MdBlock
    data class DisplayMath(val expr: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Table(val headers: List<String>, val aligns: List<TextAlign>, val rows: List<List<String>>) : MdBlock
}

private val katexSizeCache = object : LinkedHashMap<String, Pair<Float, Float>>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Float, Float>>?): Boolean = size > 128
}

// Precompiled regexes to avoid reallocating patterns during streaming and scrolling
private val INLINE_CODE_RE = Regex("`[^`\\n]+`")
private val INLINE_DOLLAR_RE = Regex("(?<!\\\\)\\$([^$\\n]{1,200})(?<!\\\\)\\$")
private val SIMPLE_DOLLAR_EXPR_RE = Regex("^[a-zA-Z0-9.,()*+-]+$")
private val HTML_MATH_RE = Regex("\\\\\\[[\\s\\S]*?\\\\\\]|\\\\\\([\\s\\S]*?\\\\\\)|\\$\\$[\\s\\S]*?\\$\\$|(?<!\\\\)\\$[^$\\n]+(?<!\\\\)\\$")
private val HTML_CODE_RE = Regex("`([^`\\n]+)`")
private val HTML_FONT_RE = Regex("\\[\\[font:([a-z-]+)\\|([\\s\\S]*?)\\]\\]", RegexOption.IGNORE_CASE)
private val HTML_LINK_RE = Regex("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)")
private val HTML_BOLD_STAR_RE = Regex("\\*\\*([^*\\n]+)\\*\\*")
private val HTML_BOLD_UND_RE = Regex("__([^_\\n]+)__")
private val HTML_STRIKE_RE = Regex("~~([^~\\n]+)~~")
private val HTML_ITALIC_RE = Regex("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])")
private val HTML_CODE_PH_RE = Regex("@@C_(\\d+)@@")
private val HTML_MATH_PH_RE = Regex("@@M_(\\d+)@@")
private val MD_HEADING_RE = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val MD_RULE_RE = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
private val MD_QUOTE_RE = Regex("^\\s{0,3}>\\s?(.*)$")
private val MD_UL_RE = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
private val MD_OL_RE = Regex("^\\s{0,3}(\\d+[.)])\\s+(.*)$")
private val MD_TABLE_SEP_RE = Regex("^\\s*\\|?[\\s:|-]*-{3,}[\\s:|-]*\\|?\\s*$")
private val MD_TABLE_CELL_SEP_RE = Regex("^:?-{3,}:?$")
private val EST_FONT_TAG_RE = Regex("\\[\\[font:[a-z-]+\\|([\\s\\S]*?)\\]\\]", RegexOption.IGNORE_CASE)
private val EST_TEX_CMD_ARG_RE = Regex("\\\\(?:text|mathrm|mathbf|mathbb|mathcal|operatorname)\\{([^}]*)\\}")
private val EST_TEX_CMD_RE = Regex("\\\\[a-zA-Z]+")
private val EST_MARKUP_CHARS_RE = Regex("[`$*_{}~\\\\]")
private val SPLIT_WS_RE = Regex("\\s+")
private val SYNTAX_TOKEN_RE = Regex("(//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Z][A-Za-z0-9_]*\\b|\\b[a-z_][a-zA-Z0-9_]*\\b)")
private val INLINE_TOKEN_RE = Regex(
    "`([^`\\n]+)`|\\[\\[font:([a-z-]+)\\|([\\s\\S]*?)\\]\\]|\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)|\\*\\*([^*\n]+)\\*\\*|__([^_\n]+)__|~~([^~\n]+)~~|\\*([^*\n]+)\\*|(https?://[^\\s<\\]]+)"
)

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

    function measureRoot(seq) {
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
        var m = measureRoot(seq);
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

    // Live-View mode (used for blocks with hyperlinks or oversized dimensions)
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

    // Shared Offscreen Bitmap Renderer mode (fast 120fps LazyColumn scrolling)
    window.__renderBitmap = function (jobId, bodyHtml, cfgJson) {
      renderSeq++;
      var seq = renderSeq;
      applyConfigAndMath(bodyHtml, cfgJson);

      var finish = function () {
        if (seq !== renderSeq) return;
        requestAnimationFrame(function () {
          if (seq !== renderSeq) return;
          try {
            var m = measureRoot(seq);
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

// ── Shared Single-WebView Bitmap Renderer + Byte-Bounded LruCache ───────────

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

@Composable
fun MarkdownMessageContent(
    rawText: String,
    textScale: Float,
    streaming: Boolean,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onPreviewHtml: (String) -> Unit,
    viewportTopProvider: () -> Float = { 0f },
    scrollTickProvider: () -> Int = { 0 },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { KatexBitmapRenderer.warmup(context) }

    val blocks = remember(rawText) { parseMarkdown(rawText) }
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val bodySize = (15.5f * textScale).sp
    val bodyLine = (23f * textScale).sp

    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { idx, block ->
                val blockStreaming = streaming && idx == blocks.lastIndex
                val cursor = if (blockStreaming) " ▍" else ""
                when (block) {
                    is MdBlock.Paragraph -> {
                        if (!blockStreaming && containsLatexMath(block.text)) {
                            KatexRichBlock(
                                htmlBody = inlineMarkdownToSafeHtml(block.text),
                                textScale = textScale,
                                isDisplay = false,
                                baseFontSp = 15.5f,
                                onOpenUrl = onOpenUrl
                            )
                        } else {
                            Text(
                                text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, lineHeight = bodyLine)
                            )
                        }
                    }
                    is MdBlock.Heading -> {
                        val baseSp = when (block.level) {
                            1 -> 21f
                            2 -> 18.5f
                            3 -> 17f
                            else -> 15.5f
                        }
                        if (!blockStreaming && containsLatexMath(block.text)) {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                KatexRichBlock(
                                    htmlBody = inlineMarkdownToSafeHtml(block.text),
                                    textScale = textScale,
                                    isDisplay = false,
                                    baseFontSp = baseSp,
                                    isBold = true,
                                    onOpenUrl = onOpenUrl
                                )
                            }
                        } else {
                            Text(
                                text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = (baseSp * textScale).sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    is MdBlock.Quote -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        if (!blockStreaming && containsLatexMath(block.text)) {
                            Box(modifier = Modifier.weight(1f)) {
                                KatexRichBlock(
                                    htmlBody = inlineMarkdownToSafeHtml(block.text),
                                    textScale = textScale,
                                    isDisplay = false,
                                    baseFontSp = 14.5f,
                                    textColorOverride = onSurfaceVariant,
                                    onOpenUrl = onOpenUrl
                                )
                            }
                        } else {
                            Text(
                                text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodySize * 0.94f, lineHeight = bodyLine * 0.94f),
                                color = onSurfaceVariant
                            )
                        }
                    }
                    is MdBlock.ListItem -> Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                        Text(
                            text = block.bullet,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        if (!blockStreaming && containsLatexMath(block.text)) {
                            Box(modifier = Modifier.weight(1f)) {
                                KatexRichBlock(
                                    htmlBody = inlineMarkdownToSafeHtml(block.text),
                                    textScale = textScale,
                                    isDisplay = false,
                                    baseFontSp = 15.5f,
                                    onOpenUrl = onOpenUrl
                                )
                            }
                        } else {
                            Text(
                                text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, lineHeight = bodyLine),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is MdBlock.Rule -> HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    is MdBlock.DisplayMath -> Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            val availWidth = maxWidth
                            if (blockStreaming) {
                                Text(
                                    text = block.expr,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = (13.5f * textScale).sp
                                    ),
                                    color = onSurfaceVariant
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    Box(
                                        modifier = Modifier.widthIn(min = availWidth),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        KatexRichBlock(
                                            htmlBody = "\\[${escapeHtml(block.expr)}\\]",
                                            textScale = textScale,
                                            isDisplay = true,
                                            availWidthDp = availWidth,
                                            baseFontSp = 15.5f,
                                            allowTouchOverride = false,
                                            onOpenUrl = onOpenUrl
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is MdBlock.Code -> CodeBlockCard(
                        lang = block.lang,
                        code = block.code,
                        textScale = textScale,
                        onCopy = { onCopy(block.code) },
                        onPreviewHtml = { onPreviewHtml(block.code) },
                        viewportTopProvider = viewportTopProvider,
                        scrollTickProvider = scrollTickProvider
                    )
                    is MdBlock.Table -> MarkdownTableCard(
                        table = block,
                        textScale = textScale,
                        streaming = blockStreaming,
                        primary = primary,
                        onCopy = onCopy,
                        onOpenUrl = onOpenUrl
                    )
                }
            }
            if (blocks.isEmpty() && streaming) {
                Text("▍", color = MaterialTheme.colorScheme.primary, fontSize = bodySize)
            }
        }
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
private fun KatexRichBlock(
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

@Composable
private fun CodeBlockCard(
    lang: String,
    code: String,
    textScale: Float,
    onCopy: () -> Unit,
    onPreviewHtml: () -> Unit,
    viewportTopProvider: () -> Float,
    scrollTickProvider: () -> Int
) {
    var expanded by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val lines = remember(code) { code.trimEnd('\n').lines() }
    val canRun = lang.equals("html", true) || lang.equals("htm", true)
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlighted = remember(code, lang, darkTheme) { highlightSyntax(code.trimEnd('\n'), lang, darkTheme) }
    val chevronRot by animateFloatAsState(if (expanded) 180f else 0f, ExpressiveSpring, label = "code_chev")
    val copyBg by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "code_copy_bg"
    )
    val copyInteraction = remember { MutableInteractionSource() }
    val runInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    ) {
        Layout(
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { expanded = !expanded }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            lang.ifEmpty { "code" }.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            lineCountLabel(lines.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        if (canRun) {
                            Surface(
                                onClick = onPreviewHtml,
                                interactionSource = runInteraction,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.springBounce(runInteraction).padding(end = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(14.dp))
                                    Text("Открыть", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                        Surface(
                            onClick = {
                                onCopy()
                                copied = true
                            },
                            interactionSource = copyInteraction,
                            shape = CircleShape,
                            color = copyBg,
                            modifier = Modifier.springBounce(copyInteraction)
                        ) {
                            AnimatedContent(
                                targetState = copied,
                                transitionSpec = {
                                    (fadeIn(tween(150)) + scaleIn(initialScale = 0.9f)) togetherWith
                                        (fadeOut(tween(110)) + scaleOut(targetScale = 0.9f))
                                },
                                label = "code_copy_anim"
                            ) { isCopied ->
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        tint = if (isCopied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        if (isCopied) "Скопировано" else "Копировать",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isCopied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).rotate(chevronRot)
                        )
                    }
                    if (expanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) + fadeIn(tween(150)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                ) {
                    val fontSize = (12.5f * textScale).sp
                    val lineHeight = (18.5f * textScale).sp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = (1..lines.size).joinToString("\n"),
                            softWrap = false,
                            modifier = Modifier.padding(start = 12.dp, end = 10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                textAlign = TextAlign.End
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        )
                        Text(
                            text = highlighted,
                            softWrap = false,
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                lineHeight = lineHeight
                            )
                        )
                    }
                }
            }
        ) { measurables, constraints ->
            val childConstraints = constraints.copy(minHeight = 0)
            val headerP = measurables[0].measure(childConstraints)
            val bodyP = measurables.getOrNull(1)?.measure(childConstraints)
            val bodyH = bodyP?.height ?: 0
            val width = maxOf(headerP.width, bodyP?.width ?: 0)
            val totalH = headerP.height + bodyH
            layout(width, totalH) {
                scrollTickProvider()
                bodyP?.placeRelative(0, headerP.height, zIndex = 0f)
                val coords = coordinates
                val stickyY = if (expanded && bodyH > headerP.height && coords != null && coords.isAttached) {
                    val topInWindow = coords.positionInWindow().y
                    val scrolledPast = (viewportTopProvider() - topInWindow).roundToInt()
                    val maxOffset = (totalH - headerP.height).coerceAtLeast(0)
                    scrolledPast.coerceIn(0, maxOffset)
                } else {
                    0
                }
                headerP.placeRelative(0, stickyY, zIndex = 2f)
            }
        }
    }
}

@Composable
private fun MarkdownTableCard(
    table: MdBlock.Table,
    textScale: Float,
    streaming: Boolean,
    primary: Color,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val chevronRot by animateFloatAsState(if (expanded) 180f else 0f, ExpressiveSpring, label = "table_chev")
    val copyBg by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "table_copy_bg"
    )
    val copyInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    val cellFontSp = 13.5f
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availWidth = maxWidth
        val colCount = table.headers.size
        val colWidths = remember(table, textScale, availWidth) {
            val minCol = (64f * textScale).coerceIn(52f, 128f)
            val maxCol = (260f * textScale).coerceIn(180f, 440f)
            val natural = (0 until colCount).map { col ->
                val headerVis = estimateVisibleTextLen(table.headers[col])
                val headerWidth = minOf(headerVis, 30) * 9.6f * textScale + 26f
                val cellWidth = table.rows.maxOfOrNull { row ->
                    val cell = row.getOrElse(col) { "" }
                    val vis = estimateVisibleTextLen(cell)
                    val longestWord = cell.split(SPLIT_WS_RE).maxOfOrNull { estimateVisibleTextLen(it) } ?: 0
                    val sampleLen = maxOf(minOf(vis, 30), minOf(longestWord, 34))
                    sampleLen * 8.6f * textScale + 26f
                } ?: 0f
                maxOf(headerWidth, cellWidth).coerceIn(minCol, maxCol).dp
            }
            val total = natural.fold(0.dp) { acc, w -> acc + w }
            if (total < availWidth && total.value > 0f) {
                val factor = availWidth / total
                natural.map { it * factor }
            } else {
                natural
            }
        }
        val totalWidth = remember(colWidths, availWidth) {
            colWidths.fold(0.dp) { acc, w -> acc + w }.coerceAtLeast(availWidth)
        }
        val scrollState = rememberScrollState()

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(spring(dampingRatio = 0.78f, stiffness = 420f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ТАБЛИЦА",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${table.rows.size} × ${table.headers.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        onClick = {
                            onCopy(tableToMarkdown(table))
                            copied = true
                        },
                        interactionSource = copyInteraction,
                        shape = CircleShape,
                        color = copyBg,
                        modifier = Modifier.springBounce(copyInteraction)
                    ) {
                        AnimatedContent(
                            targetState = copied,
                            transitionSpec = {
                                (fadeIn(tween(150)) + scaleIn(initialScale = 0.9f)) togetherWith
                                    (fadeOut(tween(110)) + scaleOut(targetScale = 0.9f))
                            },
                            label = "table_copy_anim"
                        ) { isCopied ->
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = if (isCopied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    if (isCopied) "Скопировано" else "Копировать",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCopied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp).rotate(chevronRot)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(scrollState)
                        ) {
                            Column(modifier = Modifier.width(totalWidth)) {
                                Row(
                                    modifier = Modifier
                                        .width(totalWidth)
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    table.headers.forEachIndexed { idx, h ->
                                        TableCellContent(
                                            text = h,
                                            width = colWidths[idx],
                                            align = table.aligns.getOrElse(idx) { TextAlign.Start },
                                            fontSp = cellFontSp,
                                            textScale = textScale,
                                            isHeader = true,
                                            streaming = streaming,
                                            primary = primary,
                                            onOpenUrl = onOpenUrl
                                        )
                                    }
                                }
                                table.rows.forEachIndexed { rIdx, row ->
                                    HorizontalDivider(
                                        modifier = Modifier.width(totalWidth),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                    )
                                    Row(
                                        modifier = Modifier
                                            .width(totalWidth)
                                            .background(
                                                if (rIdx % 2 == 1) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
                                                else Color.Transparent
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        row.forEachIndexed { idx, cell ->
                                            TableCellContent(
                                                text = cell,
                                                width = colWidths[idx],
                                                align = table.aligns.getOrElse(idx) { TextAlign.Start },
                                                fontSp = cellFontSp,
                                                textScale = textScale,
                                                isHeader = false,
                                                streaming = streaming,
                                                primary = primary,
                                                onOpenUrl = onOpenUrl
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun tableToMarkdown(table: MdBlock.Table): String = buildString {
    append("| ").append(table.headers.joinToString(" | ")).append(" |\n")
    append("| ").append(table.aligns.joinToString(" | ") {
        when (it) {
            TextAlign.Center -> ":---:"
            TextAlign.End -> "---:"
            else -> "---"
        }
    }).append(" |\n")
    table.rows.forEach { row ->
        append("| ").append(row.joinToString(" | ")).append(" |\n")
    }
}.trimEnd()

@Composable
private fun TableCellContent(
    text: String,
    width: Dp,
    align: TextAlign,
    fontSp: Float,
    textScale: Float,
    isHeader: Boolean,
    streaming: Boolean,
    primary: Color,
    onOpenUrl: (String) -> Unit
) {
    val cellPadH = 12.dp
    val cellPadV = 9.dp
    if (!streaming && containsLatexMath(text)) {
        Box(
            modifier = Modifier
                .width(width)
                .padding(horizontal = cellPadH, vertical = cellPadV)
        ) {
            KatexRichBlock(
                htmlBody = inlineMarkdownToSafeHtml(text),
                textScale = textScale,
                isDisplay = false,
                availWidthDp = (width - cellPadH * 2).coerceAtLeast(36.dp),
                baseFontSp = fontSp,
                textAlign = align,
                isBold = isHeader,
                allowTouchOverride = false,
                onOpenUrl = onOpenUrl
            )
        }
    } else {
        Text(
            text = buildInlineAnnotated(text, primary, onOpenUrl),
            modifier = Modifier
                .width(width)
                .padding(horizontal = cellPadH, vertical = cellPadV),
            style = if (isHeader) {
                MaterialTheme.typography.labelMedium.copy(
                    fontSize = (fontSp * textScale).sp,
                    lineHeight = ((fontSp + 5.5f) * textScale).sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                MaterialTheme.typography.bodySmall.copy(
                    fontSize = (fontSp * textScale).sp,
                    lineHeight = ((fontSp + 6f) * textScale).sp
                )
            },
            textAlign = align
        )
    }
}

private fun estimateVisibleTextLen(raw: String): Int {
    val stripped = raw
        .replace(EST_FONT_TAG_RE, "$1")
        .replace(EST_TEX_CMD_ARG_RE, "$1")
        .replace(EST_TEX_CMD_RE, "x")
        .replace(EST_MARKUP_CHARS_RE, "")
        .replace("\\(", "")
        .replace("\\)", "")
    return stripped.trim().length.coerceAtLeast(1)
}

private fun lineCountLabel(n: Int): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "$n строка"
        m10 in 2..4 && m100 !in 12..14 -> "$n строки"
        else -> "$n строк"
    }
}

private fun containsLatexMath(text: String): Boolean {
    val clean = INLINE_CODE_RE.replace(text, "")
    if (clean.contains("\\(") || clean.contains("\\[") || clean.contains("$$")) return true
    return INLINE_DOLLAR_RE.findAll(clean).any { m ->
        val inner = m.groupValues[1]
        if (inner.isEmpty() || inner.first().isWhitespace() || inner.last().isWhitespace()) {
            false
        } else {
            inner.contains('\\') || inner.contains('^') || inner.contains('_') ||
                inner.contains('=') || inner.contains('+') || inner.contains('/') ||
                SIMPLE_DOLLAR_EXPR_RE.matches(inner)
        }
    }
}

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun inlineMarkdownToSafeHtml(text: String): String {
    val mathTokens = mutableListOf<String>()
    val protectedMath = HTML_MATH_RE.replace(text) { m ->
        val token = "@@M_${mathTokens.size}@@"
        mathTokens.add(m.value)
        token
    }
    var s = escapeHtml(protectedMath)
    val codeTokens = mutableListOf<String>()
    s = HTML_CODE_RE.replace(s) { m ->
        val token = "@@C_${codeTokens.size}@@"
        codeTokens.add("<code>${m.groupValues[1]}</code>")
        token
    }
    s = HTML_FONT_RE.replace(s) { m ->
        val cls = when (m.groupValues[1].lowercase()) {
            "serif" -> "md-font-serif"
            "sans" -> "md-font-sans"
            "mono" -> "md-font-mono"
            "cursive" -> "md-font-cursive"
            "condensed" -> "md-font-condensed"
            "light" -> "md-font-light"
            "black" -> "md-font-black"
            "smallcaps" -> "md-font-smallcaps"
            else -> ""
        }
        if (cls.isEmpty()) m.groupValues[2] else "<span class=\"$cls\">${m.groupValues[2]}</span>"
    }
    s = HTML_LINK_RE.replace(s) { m ->
        "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
    }
    s = HTML_BOLD_STAR_RE.replace(s, "<strong>$1</strong>")
    s = HTML_BOLD_UND_RE.replace(s, "<strong>$1</strong>")
    s = HTML_STRIKE_RE.replace(s, "<del>$1</del>")
    s = HTML_ITALIC_RE.replace(s, "<em>$1</em>")
    s = s.replace("\n", "<br>")
    s = HTML_CODE_PH_RE.replace(s) { m -> codeTokens.getOrElse(m.groupValues[1].toInt()) { "" } }
    s = HTML_MATH_PH_RE.replace(s) { m -> escapeHtml(mathTokens.getOrElse(m.groupValues[1].toInt()) { "" }) }
    return s
}

private fun colorToHex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())

private fun parseMarkdown(raw: String): List<MdBlock> {
    if (raw.isEmpty()) return emptyList()
    val out = mutableListOf<MdBlock>()
    val parts = raw.split("```")
    parts.forEachIndexed { index, part ->
        if (index % 2 == 1) {
            val nl = part.indexOf('\n')
            val lang = if (nl >= 0) part.substring(0, nl).trim().take(24) else ""
            val code = if (nl >= 0) part.substring(nl + 1) else part
            out.add(MdBlock.Code(lang, code))
        } else {
            parseTextBlocks(part, out)
        }
    }
    return out
}

private fun parseTextBlocks(text: String, out: MutableList<MdBlock>) {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val para = mutableListOf<String>()
    fun flushPara() {
        if (para.isNotEmpty()) {
            val joined = para.joinToString("\n").trim()
            if (joined.isNotEmpty()) out.add(MdBlock.Paragraph(joined))
            para.clear()
        }
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            flushPara(); i++; continue
        }
        if (trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4 &&
            trimmed.indexOf("$$", 2) == trimmed.length - 2) {
            flushPara()
            out.add(MdBlock.DisplayMath(trimmed.drop(2).dropLast(2).trim()))
            i++; continue
        }
        if (trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 4 &&
            trimmed.indexOf("\\]", 2) == trimmed.length - 2) {
            flushPara()
            out.add(MdBlock.DisplayMath(trimmed.drop(2).dropLast(2).trim()))
            i++; continue
        }
        if (trimmed == "$$" || trimmed == "\\[" ||
            (trimmed.startsWith("$$") && !trimmed.drop(2).contains("$$")) ||
            (trimmed.startsWith("\\[") && !trimmed.drop(2).contains("\\]"))) {
            val endMarker = if (trimmed.startsWith("$$")) "$$" else "\\]"
            val buf = mutableListOf<String>()
            val firstRest = trimmed.drop(2).trim()
            if (firstRest.isNotEmpty()) buf.add(firstRest)
            var j = i + 1
            while (j < lines.size && !lines[j].trim().endsWith(endMarker)) {
                buf.add(lines[j]); j++
            }
            if (j < lines.size) {
                flushPara()
                val lastRest = lines[j].trim().removeSuffix(endMarker).trim()
                if (lastRest.isNotEmpty()) buf.add(lastRest)
                val expr = buf.joinToString("\n").trim()
                if (expr.isNotEmpty()) out.add(MdBlock.DisplayMath(expr))
                i = j + 1
                continue
            }
        }
        val tbl = parseTableAt(lines, i)
        if (tbl != null) {
            flushPara()
            out.add(tbl.first)
            i = tbl.second
            continue
        }
        val hMatch = MD_HEADING_RE.matchEntire(line)
        if (hMatch != null) {
            flushPara()
            out.add(MdBlock.Heading(hMatch.groupValues[1].length, hMatch.groupValues[2]))
            i++; continue
        }
        if (MD_RULE_RE.matches(line)) {
            flushPara(); out.add(MdBlock.Rule); i++; continue
        }
        val qMatch = MD_QUOTE_RE.matchEntire(line)
        if (qMatch != null) {
            flushPara(); out.add(MdBlock.Quote(qMatch.groupValues[1])); i++; continue
        }
        val ulMatch = MD_UL_RE.matchEntire(line)
        if (ulMatch != null) {
            flushPara(); out.add(MdBlock.ListItem("•", ulMatch.groupValues[1])); i++; continue
        }
        val olMatch = MD_OL_RE.matchEntire(line)
        if (olMatch != null) {
            flushPara(); out.add(MdBlock.ListItem(olMatch.groupValues[1], olMatch.groupValues[2])); i++; continue
        }
        para.add(line)
        i++
    }
    flushPara()
}

private fun splitTableRow(line: String, expectedCols: Int? = null): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val strict = splitPipeAware(s, protectDollars = false)
    if (expectedCols == null || strict.size <= expectedCols) return strict
    return splitPipeAware(s, protectDollars = true)
}

private fun splitPipeAware(s: String, protectDollars: Boolean): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inCode = false
    var parenDepth = 0
    var bracketDepth = 0
    var inSingleDollar = false
    var inDoubleDollar = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            val next = s[i + 1]
            if (!inCode && next == '|') {
                cur.append('|')
                i += 2
                continue
            }
            if (!inCode) {
                when (next) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '[' -> bracketDepth++
                    ']' -> if (bracketDepth > 0) bracketDepth--
                }
            }
            cur.append(c).append(next)
            i += 2
            continue
        }
        if (c == '`' && parenDepth == 0 && bracketDepth == 0) {
            inCode = !inCode
            cur.append(c)
            i++
            continue
        }
        if (protectDollars && !inCode && parenDepth == 0 && bracketDepth == 0 && c == '$') {
            if (i + 1 < s.length && s[i + 1] == '$') {
                inDoubleDollar = !inDoubleDollar
                cur.append("$$")
                i += 2
                continue
            }
            inSingleDollar = !inSingleDollar
            cur.append(c)
            i++
            continue
        }
        if (c == '|' && !inCode && parenDepth == 0 && bracketDepth == 0 && !inSingleDollar && !inDoubleDollar) {
            out.add(cur.toString().trim())
            cur.setLength(0)
        } else {
            cur.append(c)
        }
        i++
    }
    out.add(cur.toString().trim())
    return out
}

private fun parseTableAt(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
    val head = lines.getOrNull(start) ?: return null
    val sep = lines.getOrNull(start + 1) ?: return null
    if (!head.contains("|") || !MD_TABLE_SEP_RE.matches(sep)) return null
    val sepCells = splitTableRow(sep)
    if (sepCells.isEmpty() || !sepCells.all { MD_TABLE_CELL_SEP_RE.matches(it) }) return null
    val headers = splitTableRow(head, sepCells.size)
    if (headers.size != sepCells.size) return null
    val aligns = sepCells.map {
        val l = it.startsWith(":"); val r = it.endsWith(":")
        when { l && r -> TextAlign.Center; r -> TextAlign.End; else -> TextAlign.Start }
    }
    val rows = mutableListOf<List<String>>()
    var i = start + 2
    while (i < lines.size && lines[i].trim().isNotEmpty() && lines[i].contains("|")) {
        val cells = splitTableRow(lines[i], headers.size).toMutableList()
        while (cells.size < headers.size) cells.add("")
        rows.add(cells.take(headers.size))
        i++
    }
    return MdBlock.Table(headers, aligns, rows) to i
}

private fun buildInlineAnnotated(
    text: String,
    accentColor: Color,
    onOpenUrl: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
    var idx = 0
    for (m in INLINE_TOKEN_RE.findAll(text)) {
        if (m.range.first > idx) append(text.substring(idx, m.range.first))
        val v = m.groupValues
        when {
            v[1].isNotEmpty() -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accentColor, fontWeight = FontWeight.Medium))
                append(v[1])
                pop()
            }
            v[2].isNotEmpty() -> {
                val style = when (v[2].lowercase()) {
                    "serif" -> SpanStyle(fontFamily = FontFamily.Serif)
                    "sans" -> SpanStyle(fontFamily = FontFamily.SansSerif)
                    "mono" -> SpanStyle(fontFamily = FontFamily.Monospace)
                    "cursive" -> SpanStyle(fontFamily = FontFamily.Cursive)
                    "light" -> SpanStyle(fontWeight = FontWeight.Light)
                    "black" -> SpanStyle(fontWeight = FontWeight.Black)
                    else -> SpanStyle(fontWeight = FontWeight.Medium)
                }
                pushStyle(style); append(v[3]); pop()
            }
            v[4].isNotEmpty() && v[5].isNotEmpty() -> {
                val url = v[5]
                pushLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline))) { onOpenUrl(url) })
                append(v[4])
                pop()
            }
            v[6].isNotEmpty() || v[7].isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(v[6].ifEmpty { v[7] })
                pop()
            }
            v[8].isNotEmpty() -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(v[8])
                pop()
            }
            v[9].isNotEmpty() -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(v[9])
                pop()
            }
            v[10].isNotEmpty() -> {
                val clean = v[10].trimEnd('.', ',', ';', ':', '!', '?', ')')
                val tail = v[10].removePrefix(clean)
                pushLink(LinkAnnotation.Url(clean, TextLinkStyles(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline))) { onOpenUrl(clean) })
                append(clean)
                pop()
                append(tail)
            }
            else -> append(m.value)
        }
        idx = m.range.last + 1
    }
    if (idx < text.length) append(text.substring(idx))
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "fn", "let", "mut", "pub", "impl", "struct", "enum", "trait", "match",
    "if", "else", "for", "while", "loop", "return", "break", "continue", "import", "package",
    "use", "mod", "crate", "async", "await", "class", "interface", "object", "data", "sealed",
    "def", "lambda", "from", "as", "with", "try", "catch", "finally", "throw", "throws",
    "const", "function", "export", "default", "true", "false", "null", "None", "self", "this",
    "public", "private", "static", "final", "void", "new", "override", "suspend", "when"
)

private fun highlightSyntax(code: String, lang: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
    val cComment = if (dark) Color(0xFF7F848E) else Color(0xFF6B7280)
    val cKeyword = if (dark) Color(0xFFC792EA) else Color(0xFF7C3AED)
    val cString = if (dark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
    val cNumber = if (dark) Color(0xFFFFCC80) else Color(0xFFB45309)
    val cType = if (dark) Color(0xFF82AAFF) else Color(0xFF1D4ED8)
    var idx = 0
    for (m in SYNTAX_TOKEN_RE.findAll(code)) {
        if (m.range.first > idx) append(code.substring(idx, m.range.first))
        val tok = m.value
        val color = when {
            tok.startsWith("//") || tok.startsWith("/*") || (tok.startsWith("#") && !lang.equals("c", true) && !lang.equals("cpp", true)) -> cComment
            tok.startsWith("\"") || tok.startsWith("'") -> cString
            tok.first().isDigit() -> cNumber
            tok in KEYWORDS -> cKeyword
            tok.first().isUpperCase() -> cType
            else -> Color.Unspecified
        }
        if (color != Color.Unspecified) {
            pushStyle(SpanStyle(color = color, fontStyle = if (color == cComment) FontStyle.Italic else FontStyle.Normal))
            append(tok)
            pop()
        } else {
            append(tok)
        }
        idx = m.range.last + 1
    }
    if (idx < code.length) append(code.substring(idx))
}
