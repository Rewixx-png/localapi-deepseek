package dev.localapi.deepseek

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

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
    val bodySize = (15.5f * textScale).sp
    val bodyLine = (23f * textScale).sp

    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { idx, block ->
                val cursor = if (streaming && idx == blocks.lastIndex) " ▍" else ""
                when (block) {
                    is MdBlock.Paragraph -> {
                        if (!streaming && containsLatexMath(block.text)) {
                            KatexRichBlock(
                                htmlBody = inlineMarkdownToSafeHtml(block.text),
                                textScale = textScale,
                                isDisplay = false
                            )
                        } else {
                            Text(
                                text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, lineHeight = bodyLine)
                            )
                        }
                    }
                    is MdBlock.Heading -> {
                        val size = when (block.level) {
                            1 -> 21.sp * textScale
                            2 -> 18.5.sp * textScale
                            3 -> 17.sp * textScale
                            else -> 15.5.sp * textScale
                        }
                        Text(
                            text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = size, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = 4.dp)
                        )
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
                        Text(
                            text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodySize * 0.94f, lineHeight = bodyLine * 0.94f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is MdBlock.ListItem -> Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                        Text(
                            text = block.bullet,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        if (!streaming && containsLatexMath(block.text)) {
                            Box(modifier = Modifier.weight(1f)) {
                                KatexRichBlock(
                                    htmlBody = inlineMarkdownToSafeHtml(block.text),
                                    textScale = textScale,
                                    isDisplay = false
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
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            if (streaming) {
                                Text(
                                    text = block.expr,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                KatexRichBlock(
                                    htmlBody = "\\[${escapeHtml(block.expr)}\\]",
                                    textScale = textScale,
                                    isDisplay = true
                                )
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
                    is MdBlock.Table -> MarkdownTableCard(block, textScale, primary, onOpenUrl)
                }
            }
            if (blocks.isEmpty() && streaming) {
                Text("▍", color = MaterialTheme.colorScheme.primary, fontSize = bodySize)
            }
        }
    }
}

private class KatexHeightBridge(private val onHeightPx: (Float) -> Unit) {
    @JavascriptInterface
    fun setHeight(px: Float) {
        onHeightPx(px)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun KatexRichBlock(
    htmlBody: String,
    textScale: Float,
    isDisplay: Boolean
) {
    val textColorHex = colorToHex(MaterialTheme.colorScheme.onSurface)
    val codeColorHex = colorToHex(MaterialTheme.colorScheme.primary)
    val linkColorHex = colorToHex(MaterialTheme.colorScheme.primary)
    val fontSizePx = 15.5f * textScale
    var heightDp by remember(htmlBody, textScale) {
        mutableFloatStateOf(if (isDisplay) 52f else 28f)
    }

    val documentHtml = remember(htmlBody, textColorHex, codeColorHex, linkColorHex, fontSizePx) {
        val bodyJson = JSONObject.quote(htmlBody)
        """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <link rel="stylesheet" href="katex/katex.min.css">
        <style>
          html, body {
            margin: 0; padding: 0;
            background: transparent;
            color: $textColorHex;
            font-family: Roboto, system-ui, sans-serif;
            font-size: ${fontSizePx}px;
            line-height: 1.48;
            overflow-x: hidden;
            overflow-y: hidden;
            word-wrap: break-word;
          }
          #root { width: 100%; }
          code {
            font-family: ui-monospace, "Roboto Mono", monospace;
            font-size: 0.9em;
            color: $codeColorHex;
            font-weight: 500;
          }
          a { color: $linkColorHex; text-decoration: underline; }
          .katex-display {
            margin: 2px 0 !important;
            overflow-x: auto;
            overflow-y: hidden;
            padding: 2px 0;
          }
          .katex { color: inherit; }
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
                var h = Math.ceil(root.getBoundingClientRect().height);
                if (h > 0 && window.KatexBridge) window.KatexBridge.setHeight(h);
              } catch (e) {}
            }
            report();
            if (document.fonts && document.fonts.ready) {
              document.fonts.ready.then(report);
            }
            setTimeout(report, 60);
            setTimeout(report, 220);
          })();
        </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
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
                addJavascriptInterface(
                    KatexHeightBridge { px ->
                        post {
                            if (px > 4f) heightDp = px + 2f
                        }
                    },
                    "KatexBridge"
                )
            }
        },
        update = { web ->
            if (web.tag != documentHtml) {
                web.tag = documentHtml
                web.loadDataWithBaseURL("file:///android_asset/web/", documentHtml, "text/html", "utf-8", null)
            }
        },
        onRelease = { web ->
            web.removeJavascriptInterface("KatexBridge")
            web.destroy()
        },
        modifier = Modifier.fillMaxWidth().height(heightDp.dp)
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
    val lines = remember(code) { code.trimEnd('\n').lines() }
    val canRun = lang.equals("html", true) || lang.equals("htm", true)
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlighted = remember(code, lang, darkTheme) { highlightSyntax(code.trimEnd('\n'), lang, darkTheme) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
                    TextButton(onClick = onPreviewHtml, modifier = Modifier.height(30.dp)) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Открыть", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onCopy, modifier = Modifier.height(30.dp)) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Копировать", style = MaterialTheme.typography.labelSmall)
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
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
    primary: Color,
    onOpenUrl: (String) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availWidth = maxWidth
        val colCount = table.headers.size
        val colWidths = remember(table, textScale, availWidth) {
            val natural = (0 until colCount).map { col ->
                val headerLen = table.headers[col].length
                val maxCellLen = table.rows.maxOfOrNull { row ->
                    val cell = row.getOrElse(col) { "" }
                    // Estimate reasonable single-line or readable column width
                    val longestToken = cell.split(Regex("\\s+")).maxOfOrNull { it.length } ?: 0
                    maxOf(minOf(cell.length, 32), minOf(longestToken + 2, 34))
                } ?: 0
                val chars = maxOf(headerLen + 2, maxCellLen)
                (chars * 8.2f * textScale + 22f).coerceIn(92f, 240f).dp
            }
            val total = natural.fold(0.dp) { acc, w -> acc + w }
            if (total < availWidth && total.value > 0f) {
                val factor = availWidth / total
                natural.map { it * factor }
            } else {
                natural
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .height(IntrinsicSize.Min)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    table.headers.forEachIndexed { idx, h ->
                        Text(
                            text = buildInlineAnnotated(h, primary, onOpenUrl),
                            modifier = Modifier
                                .width(colWidths[idx])
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = (13f * textScale).sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            textAlign = table.aligns.getOrElse(idx) { TextAlign.Start }
                        )
                    }
                }
                table.rows.forEachIndexed { rIdx, row ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier
                            .height(IntrinsicSize.Min)
                            .background(if (rIdx % 2 == 1) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f) else Color.Transparent)
                    ) {
                        row.forEachIndexed { idx, cell ->
                            Text(
                                text = buildInlineAnnotated(cell, primary, onOpenUrl),
                                modifier = Modifier
                                    .width(colWidths[idx])
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = (13f * textScale).sp,
                                    lineHeight = (19f * textScale).sp
                                ),
                                textAlign = table.aligns.getOrElse(idx) { TextAlign.Start }
                            )
                        }
                    }
                }
            }
        }
    }
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
    if (text.contains("\\(") || text.contains("\\[") || text.contains("$$")) return true
    val dollarMatch = Regex("\\$([^$\\n]{1,180})\\$").find(text) ?: return false
    val inner = dollarMatch.groupValues[1]
    return inner.contains('\\') || inner.contains('^') || inner.contains('_') ||
        inner.contains('=') || inner.contains('+') || inner.contains('/') ||
        Regex("^[a-zA-Z0-9\\s.,()*+-]+$").matches(inner)
}

private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun inlineMarkdownToSafeHtml(text: String): String {
    val mathTokens = mutableListOf<String>()
    val mathRe = Regex("\\\\\\[[\\s\\S]*?\\\\\\]|\\\\\\([\\s\\S]*?\\\\\\)|\\$\\$[\\s\\S]*?\\$\\$|\\$[^$\\n]+\\$")
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
    s = Regex("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)").replace(s) { m ->
        "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
    }
    s = Regex("\\*\\*([^*\\n]+)\\*\\*").replace(s, "<strong>$1</strong>")
    s = Regex("__([^_\\n]+)__").replace(s, "<strong>$1</strong>")
    s = Regex("\\*([^*\\n]+)\\*").replace(s, "<em>$1</em>")
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
        if ((trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4) ||
            (trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 4)) {
            flushPara()
            out.add(MdBlock.DisplayMath(trimmed.drop(2).dropLast(2).trim()))
            i++; continue
        }
        if (trimmed == "$$" || trimmed == "\\[") {
            flushPara()
            val endMarker = if (trimmed == "$$") "$$" else "\\]"
            val buf = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size && lines[j].trim() != endMarker) {
                buf.add(lines[j]); j++
            }
            if (j < lines.size) {
                out.add(MdBlock.DisplayMath(buf.joinToString("\n").trim()))
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
                // Clean inline monospace without jagged per-line dark rectangles
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
