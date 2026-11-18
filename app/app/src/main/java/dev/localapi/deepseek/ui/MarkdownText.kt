package dev.localapi.deepseek

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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

private val katexSizeCache = object : LinkedHashMap<String, Pair<Float, Float>>(96, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Float, Float>>?): Boolean = size > 96
}

@Composable
fun MarkdownMessageContent(
    rawText: String,
    textScale: Float,
    streaming: Boolean,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onPreviewHtml: (String) -> Unit,
    modifier: Modifier = Modifier
) {
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
                    is MdBlock.Code -> CodeBlockCard(
                        lang = block.lang,
                        code = block.code,
                        textScale = textScale,
                        onCopy = { onCopy(block.code) },
                        onPreviewHtml = { onPreviewHtml(block.code) }
                    )
                    is MdBlock.Table -> MarkdownTableCard(
                        table = block,
                        textScale = textScale,
                        streaming = blockStreaming,
                        primary = primary,
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
    @Volatile var lastRenderedHtml: String? = null
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

@SuppressLint("SetJavaScriptEnabled")
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
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val uiScale = LocalUiScale.current

    val textColorHex = colorToHex(textColorOverride ?: cs.onSurface)
    val accentHex = colorToHex(cs.primary)
    // Match Compose's physical font size: baseSp * textScale * fontScale * (systemDensity * uiScale).
    // WebView 1 CSS px = systemDensity physical px (with textZoom=100 & text-size-adjust: 100%).
    val fontSizeCssPx = (baseFontSp * textScale * uiScale * density.fontScale).coerceAtLeast(6f)
    val cssTextAlign = when (textAlign) {
        TextAlign.Center -> "center"
        TextAlign.End, TextAlign.Right -> "right"
        else -> "left"
    }
    val cssFontWeight = if (isBold) "600" else "400"
    val availWidthVal = availWidthDp.value.coerceAtLeast(40f)
    val widthBucket = (availWidthVal * density.density / 8f).roundToInt()
    val scaleBucket = (fontSizeCssPx * 10f).roundToInt()
    val cacheKey = remember(htmlBody, scaleBucket, widthBucket, isDisplay, cssTextAlign, cssFontWeight) {
        "${htmlBody.hashCode()}:${htmlBody.length}:$scaleBucket:$widthBucket:$isDisplay:$cssTextAlign:$cssFontWeight"
    }
    val cached = katexSizeCache[cacheKey]
    val initialHeight = cached?.second ?: ((if (isDisplay) 46f else 24f) * textScale).coerceAtLeast(22f)
    val initialWidth = cached?.first ?: availWidthVal

    var heightDp by remember(cacheKey) { mutableFloatStateOf(initialHeight) }
    var widthDp by remember(cacheKey) { mutableFloatStateOf(initialWidth) }
    val hasLinks = remember(htmlBody) { htmlBody.contains("<a href=") }
    val allowTouch = allowTouchOverride ?: hasLinks

    val documentHtml = remember(htmlBody, textColorHex, accentHex, fontSizeCssPx, isDisplay, cssTextAlign, cssFontWeight) {
        val bodyJson = JSONObject.quote(htmlBody)
        val rootCss = if (isDisplay) {
            """
            #root {
              display: inline-block;
              min-width: 100%;
              padding: 6px 4px;
              white-space: nowrap;
              text-align: center;
            }
            .katex-display {
              display: inline-block;
              min-width: 100%;
              margin: 0 !important;
              padding: 4px 2px;
              overflow: visible !important;
              text-align: center;
            }
            .katex-display > .katex {
              display: inline-block;
              white-space: nowrap;
            }
            """.trimIndent()
        } else {
            """
            #root {
              display: flow-root;
              width: 100%;
              padding: 2px 0 4px 0;
              text-align: $cssTextAlign;
              word-wrap: break-word;
              overflow-wrap: anywhere;
            }
            .katex-display {
              margin: 6px 0 !important;
              padding: 4px 0;
              overflow-x: auto;
              overflow-y: visible;
            }
            """.trimIndent()
        }
        """
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
            color: $textColorHex;
            font-family: Roboto, system-ui, sans-serif;
            font-size: ${fontSizeCssPx}px;
            font-weight: $cssFontWeight;
            line-height: 1.48;
            -webkit-text-size-adjust: 100%;
            text-size-adjust: 100%;
            overflow: hidden;
          }
          $rootCss
          code {
            font-family: ui-monospace, "Roboto Mono", monospace;
            font-size: 0.9em;
            color: $accentHex;
            font-weight: 500;
          }
          a { color: $accentHex; text-decoration: underline; }
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
        </head>
        <body>
        <div id="root"></div>
        <script src="katex/katex.min.js"></script>
        <script src="katex/contrib/auto-render.min.js"></script>
        <script>
          (function () {
            var root = document.getElementById("root");
            root.innerHTML = $bodyJson;
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
            function report() {
              try {
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
                var cssH = Math.ceil(Math.max(rect.height, root.scrollHeight, maxBottom - minTop)) + 2;
                var cssW = Math.ceil(Math.max(rect.width, root.scrollWidth, maxRight - minLeft)) + 2;
                var dpr = window.devicePixelRatio || 1;
                if (cssH > 0 && window.KatexBridge) {
                  window.KatexBridge.setSize(Math.ceil(cssW * dpr), Math.ceil(cssH * dpr));
                }
              } catch (e) {}
            }
            report();
            if (window.ResizeObserver) {
              try { new ResizeObserver(report).observe(root); } catch (e) {}
            }
            if (document.fonts && document.fonts.ready) {
              document.fonts.ready.then(report);
            }
            setTimeout(report, 40);
            setTimeout(report, 160);
            setTimeout(report, 360);
          })();
        </script>
        </body>
        </html>
        """.trimIndent()
    }

    val boxModifier = if (isDisplay) {
        Modifier
            .width(widthDp.coerceAtLeast(availWidthVal).coerceAtMost(2400f).dp)
            .height(heightDp.dp)
    } else {
        Modifier
            .width(availWidthVal.dp)
            .height(heightDp.dp)
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
                }
                addJavascriptInterface(bridge, "KatexBridge")
            }
        },
        onReset = { web ->
            web.bridge.onSize = null
            web.openUrlHandler = null
            web.lastRenderedHtml = null
            web.allowTouch = false
        },
        onRelease = { web ->
            web.bridge.onSize = null
            web.openUrlHandler = null
            web.removeJavascriptInterface("KatexBridge")
            web.destroy()
        },
        update = { web ->
            web.allowTouch = allowTouch
            web.openUrlHandler = onOpenUrl
            val expectedHtml = documentHtml
            web.bridge.onSize = { physW, physH ->
                web.post {
                    if (web.lastRenderedHtml != expectedHtml) return@post
                    if (physH > 4f && physW > 4f) {
                        val nextH = (physH / density.density) + 2f
                        val nextW = if (isDisplay) {
                            ((physW / density.density) + 2f).coerceAtLeast(availWidthVal).coerceAtMost(2400f)
                        } else {
                            availWidthVal
                        }
                        val hChanged = abs(nextH - heightDp) > 1.2f
                        val wChanged = isDisplay && abs(nextW - widthDp) > 1.5f
                        if (hChanged || wChanged) {
                            if (hChanged) heightDp = nextH
                            if (wChanged) widthDp = nextW
                            katexSizeCache[cacheKey] = widthDp to heightDp
                        }
                    }
                }
            }
            if (web.lastRenderedHtml != documentHtml) {
                web.lastRenderedHtml = documentHtml
                web.loadDataWithBaseURL("file:///android_asset/web/", documentHtml, "text/html", "utf-8", null)
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
    onPreviewHtml: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val lines = remember(code) { code.trimEnd('\n').lines() }
    val canRun = lang.equals("html", true) || lang.equals("htm", true)
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlighted = remember(code, lang, darkTheme) { highlightSyntax(code.trimEnd('\n'), lang, darkTheme) }
    val chevronRot by animateFloatAsState(if (expanded) 180f else 0f, ExpressiveSpring, label = "code_chev")
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
            .animateContentSize(spring(dampingRatio = 0.78f, stiffness = 420f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
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
                    color = if (copied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.springBounce(copyInteraction)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            tint = if (copied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            if (copied) "Скопировано" else "Копировать",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (copied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
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
    }
}

@Composable
private fun MarkdownTableCard(
    table: MdBlock.Table,
    textScale: Float,
    streaming: Boolean,
    primary: Color,
    onOpenUrl: (String) -> Unit
) {
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
                    val longestWord = cell.split(Regex("\\s+")).maxOfOrNull { estimateVisibleTextLen(it) } ?: 0
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
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
            ) {
                Column(modifier = Modifier.width(totalWidth)) {
                    Row(
                        modifier = Modifier
                            .width(totalWidth)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                                    if (rIdx % 2 == 1) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
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
        .replace(Regex("\\[\\[font:[a-z-]+\\|([\\s\\S]*?)\\]\\]", RegexOption.IGNORE_CASE), "$1")
        .replace(Regex("\\\\(?:text|mathrm|mathbf|mathbb|mathcal|operatorname)\\{([^}]*)\\}"), "$1")
        .replace(Regex("\\\\[a-zA-Z]+"), "x")
        .replace(Regex("[`$*_{}~\\\\]"), "")
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

private val INLINE_CODE_RE = Regex("`[^`\\n]+`")
private val INLINE_DOLLAR_RE = Regex("(?<!\\\\)\\$([^$\\n]{1,200})(?<!\\\\)\\$")

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
                Regex("^[a-zA-Z0-9.,()*+-]+$").matches(inner)
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
    val mathRe = Regex("\\\\\\[[\\s\\S]*?\\\\\\]|\\\\\\([\\s\\S]*?\\\\\\)|\\$\\$[\\s\\S]*?\\$\\$|(?<!\\\\)\\$[^$\\n]+(?<!\\\\)\\$")
    val protectedMath = mathRe.replace(text) { m ->
        val token = "@@M_${mathTokens.size}@@"
        mathTokens.add(m.value)
        token
    }
    var s = escapeHtml(protectedMath)
    val codeTokens = mutableListOf<String>()
    s = Regex("`([^`\\n]+)`").replace(s) { m ->
        val token = "@@C_${codeTokens.size}@@"
        codeTokens.add("<code>${m.groupValues[1]}</code>")
        token
    }
    s = Regex("\\[\\[font:([a-z-]+)\\|([\\s\\S]*?)\\]\\]", RegexOption.IGNORE_CASE).replace(s) { m ->
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
    s = Regex("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)").replace(s) { m ->
        "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
    }
    s = Regex("\\*\\*([^*\\n]+)\\*\\*").replace(s, "<strong>$1</strong>")
    s = Regex("__([^_\\n]+)__").replace(s, "<strong>$1</strong>")
    s = Regex("~~([^~\\n]+)~~").replace(s, "<del>$1</del>")
    s = Regex("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])").replace(s, "<em>$1</em>")
    s = s.replace("\n", "<br>")
    s = Regex("@@C_(\\d+)@@").replace(s) { m -> codeTokens.getOrElse(m.groupValues[1].toInt()) { "" } }
    s = Regex("@@M_(\\d+)@@").replace(s) { m -> escapeHtml(mathTokens.getOrElse(m.groupValues[1].toInt()) { "" }) }
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
        val hMatch = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$").matchEntire(line)
        if (hMatch != null) {
            flushPara()
            out.add(MdBlock.Heading(hMatch.groupValues[1].length, hMatch.groupValues[2]))
            i++; continue
        }
        if (Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$").matches(line)) {
            flushPara(); out.add(MdBlock.Rule); i++; continue
        }
        val qMatch = Regex("^\\s{0,3}>\\s?(.*)$").matchEntire(line)
        if (qMatch != null) {
            flushPara(); out.add(MdBlock.Quote(qMatch.groupValues[1])); i++; continue
        }
        val ulMatch = Regex("^\\s{0,3}[-*+]\\s+(.*)$").matchEntire(line)
        if (ulMatch != null) {
            flushPara(); out.add(MdBlock.ListItem("•", ulMatch.groupValues[1])); i++; continue
        }
        val olMatch = Regex("^\\s{0,3}(\\d+[.)])\\s+(.*)$").matchEntire(line)
        if (olMatch != null) {
            flushPara(); out.add(MdBlock.ListItem(olMatch.groupValues[1], olMatch.groupValues[2])); i++; continue
        }
        para.add(line)
        i++
    }
    flushPara()
}

private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split("|").map { it.trim() }
}

private fun parseTableAt(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
    val head = lines.getOrNull(start) ?: return null
    val sep = lines.getOrNull(start + 1) ?: return null
    if (!head.contains("|") || !Regex("^\\s*\\|?[\\s:|-]*-{3,}[\\s:|-]*\\|?\\s*$").matches(sep)) return null
    val headers = splitTableRow(head)
    val sepCells = splitTableRow(sep)
    if (headers.isEmpty() || sepCells.size != headers.size) return null
    if (!sepCells.all { Regex("^:?-{3,}:?$").matches(it) }) return null
    val aligns = sepCells.map {
        val l = it.startsWith(":"); val r = it.endsWith(":")
        when { l && r -> TextAlign.Center; r -> TextAlign.End; else -> TextAlign.Start }
    }
    val rows = mutableListOf<List<String>>()
    var i = start + 2
    while (i < lines.size && lines[i].trim().isNotEmpty() && lines[i].contains("|")) {
        val cells = splitTableRow(lines[i]).toMutableList()
        while (cells.size < headers.size) cells.add("")
        rows.add(cells.take(headers.size))
        i++
    }
    return MdBlock.Table(headers, aligns, rows) to i
}

private val INLINE_TOKEN_RE = Regex(
    "`([^`\\n]+)`|\\[\\[font:([a-z-]+)\\|([\\s\\S]*?)\\]\\]|\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)|\\*\\*([^*\n]+)\\*\\*|__([^_\n]+)__|~~([^~\n]+)~~|\\*([^*\n]+)\\*|(https?://[^\\s<\\]]+)"
)

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
    val tokenRe = Regex("(//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Z][A-Za-z0-9_]*\\b|\\b[a-z_][a-zA-Z0-9_]*\\b)")
    var idx = 0
    for (m in tokenRe.findAll(code)) {
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
