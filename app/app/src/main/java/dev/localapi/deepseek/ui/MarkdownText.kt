package dev.localapi.deepseek

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val bodySize = (15.5f * textScale).sp
    val bodyLine = (23f * textScale).sp

    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { idx, block ->
                val cursor = if (streaming && idx == blocks.lastIndex) " ▍" else ""
                when (block) {
                    is MdBlock.Paragraph -> Text(
                        text = buildInlineAnnotated(block.text + cursor, primary, codeBg, onOpenUrl),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, lineHeight = bodyLine)
                    )
                    is MdBlock.Heading -> {
                        val size = when (block.level) {
                            1 -> 21.sp * textScale
                            2 -> 18.5.sp * textScale
                            3 -> 17.sp * textScale
                            else -> 15.5.sp * textScale
                        }
                        Text(
                            text = buildInlineAnnotated(block.text + cursor, primary, codeBg, onOpenUrl),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = size, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    is MdBlock.Quote -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = buildInlineAnnotated(block.text + cursor, primary, codeBg, onOpenUrl),
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
                        Text(
                            text = buildInlineAnnotated(block.text + cursor, primary, codeBg, onOpenUrl),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, lineHeight = bodyLine),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    is MdBlock.Rule -> HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    is MdBlock.DisplayMath -> Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = beautifyLatex(block.expr),
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontSize = (16.5f * textScale).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    is MdBlock.Code -> CodeBlockCard(
                        lang = block.lang,
                        code = block.code,
                        textScale = textScale,
                        onCopy = { onCopy(block.code) },
                        onPreviewHtml = { onPreviewHtml(block.code) }
                    )
                    is MdBlock.Table -> MarkdownTableCard(block, textScale, primary, codeBg, onOpenUrl)
                }
            }
            if (blocks.isEmpty() && streaming) {
                Text("▍", color = MaterialTheme.colorScheme.primary, fontSize = bodySize)
            }
        }
    }
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
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(
                        text = (1..lines.size).joinToString("\n"),
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
                            .height((lines.size * 18.5f * textScale).dp.coerceAtLeast(18.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                    Text(
                        text = highlighted,
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
    codeBg: Color,
    onOpenUrl: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                table.headers.forEachIndexed { idx, h ->
                    Text(
                        text = buildInlineAnnotated(h, primary, codeBg, onOpenUrl),
                        modifier = Modifier.width(128.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = (13 * textScale).sp),
                        textAlign = table.aligns.getOrElse(idx) { TextAlign.Start }
                    )
                }
            }
            table.rows.forEachIndexed { rIdx, row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.background(
                        if (rIdx % 2 == 1) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent
                    )
                ) {
                    row.forEachIndexed { idx, cell ->
                        Text(
                            text = buildInlineAnnotated(cell, primary, codeBg, onOpenUrl),
                            modifier = Modifier.width(128.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = (13 * textScale).sp),
                            textAlign = table.aligns.getOrElse(idx) { TextAlign.Start }
                        )
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
    "`([^`\\n]+)`|\\[\\[font:([a-z-]+)\\|([\\s\\S]*?)\\]\\]|\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)|\\\\\\((.+?)\\\\\\)|\\$([^$\\n]+)\\$|\\*\\*([^*\n]+)\\*\\*|__([^_\n]+)__|~~([^~\n]+)~~|\\*([^*\n]+)\\*|(https?://[^\\s<\\]]+)"
)

private fun buildInlineAnnotated(
    text: String,
    linkColor: Color,
    codeBg: Color,
    onOpenUrl: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
    var idx = 0
    for (m in INLINE_TOKEN_RE.findAll(text)) {
        if (m.range.first > idx) append(text.substring(idx, m.range.first))
        val v = m.groupValues
        when {
            v[1].isNotEmpty() -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontWeight = FontWeight.Medium))
                append(" ${v[1]} ")
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
                pushLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) { onOpenUrl(url) })
                append(v[4])
                pop()
            }
            v[6].isNotEmpty() || v[7].isNotEmpty() -> {
                val math = beautifyLatex(v[6].ifEmpty { v[7] })
                pushStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, background = codeBg.copy(alpha = 0.55f)))
                append(math)
                pop()
            }
            v[8].isNotEmpty() || v[9].isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(v[8].ifEmpty { v[9] })
                pop()
            }
            v[10].isNotEmpty() -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(v[10])
                pop()
            }
            v[11].isNotEmpty() -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(v[11])
                pop()
            }
            v[12].isNotEmpty() -> {
                val clean = v[12].trimEnd('.', ',', ';', ':', '!', '?', ')')
                val tail = v[12].removePrefix(clean)
                pushLink(LinkAnnotation.Url(clean, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) { onOpenUrl(clean) })
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

private fun beautifyLatex(raw: String): String {
    var s = raw
    val map = listOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\pi" to "π", "\\sigma" to "σ", "\\phi" to "φ", "\\omega" to "ω",
        "\\Delta" to "Δ", "\\Sigma" to "Σ", "\\Omega" to "Ω", "\\Pi" to "Π",
        "\\times" to "×", "\\div" to "÷", "\\cdot" to "·", "\\pm" to "±",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
        "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
        "\\infty" to "∞", "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←",
        "\\Rightarrow" to "⇒", "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂",
        "\\forall" to "∀", "\\exists" to "∃", "\\partial" to "∂", "\\nabla" to "∇",
        "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\sqrt" to "√"
    )
    map.forEach { (k, v) -> s = s.replace(k, v) }
    s = s.replace(Regex("\\\\frac\\{([^}]*)\\}\\{([^}]*)\\}"), "($1)/($2)")
    s = s.replace(Regex("\\\\text\\{([^}]*)\\}"), "$1")
    s = s.replace("^2", "²").replace("^3", "³").replace("^n", "ⁿ")
    return s.replace("{", "").replace("}", "")
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
