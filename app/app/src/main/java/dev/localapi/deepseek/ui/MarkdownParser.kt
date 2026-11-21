package dev.localapi.deepseek.ui

import androidx.compose.ui.text.style.TextAlign

/**
 * Pure Kotlin Markdown / GFM-table / LaTeX block & inline HTML converter.
 * Free of Android/Compose runtime dependencies so it is unit-tested directly
 * on the JVM.
 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val bullet: String, val text: String) : MdBlock
    data object Rule : MdBlock
    data class DisplayMath(val expr: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Table(
        val headers: List<String>,
        val aligns: List<TextAlign>,
        val rows: List<List<String>>
    ) : MdBlock
}

private val INLINE_CODE_RE = Regex("`[^`\\n]+`")
private val INLINE_DOLLAR_RE = Regex("(?<!\\\\)\\$([^$\\n]{1,200})(?<!\\\\)\\$")
private val SIMPLE_DOLLAR_EXPR_RE = Regex("^[a-zA-Z0-9.,()*+-]+$")
private val HTML_MATH_RE =
    Regex("\\\\\\[[\\s\\S]*?\\\\\\]|\\\\\\([\\s\\S]*?\\\\\\)|\\$\\$[\\s\\S]*?\\$\\$|(?<!\\\\)\\$[^$\\n]+(?<!\\\\)\\$")
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
private val MD_FENCE_OPEN_RE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^`~\\n]*)$")
private val MD_TABLE_SEP_RE = Regex("^\\s*\\|?[\\s:|-]*-{3,}[\\s:|-]*\\|?\\s*$")
private val MD_TABLE_CELL_SEP_RE = Regex("^:?-{3,}:?$")
private val EST_FONT_TAG_RE = Regex("\\[\\[font:[a-z-]+\\|([\\s\\S]*?)\\]\\]", RegexOption.IGNORE_CASE)
private val EST_TEX_CMD_ARG_RE = Regex("\\\\(?:text|mathrm|mathbf|mathbb|mathcal|operatorname)\\{([^}]*)\\}")
private val EST_TEX_CMD_RE = Regex("\\\\[a-zA-Z]+")
private val EST_MARKUP_CHARS_RE = Regex("[`$*_{}~\\\\]")
internal val SPLIT_WS_RE = Regex("\\s+")

internal fun containsLatexMath(text: String): Boolean {
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

internal fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/**
 * Converts inline Markdown (with LaTeX delimiters intact) into safe HTML for
 * KaTeX WebView rendering. Inline code spans are protected *before* math so
 * literal `$...$` inside backticks is never mis-parsed as LaTeX.
 */
internal fun inlineMarkdownToSafeHtml(text: String): String {
    val codeTokens = mutableListOf<String>()
    val protectedCode = HTML_CODE_RE.replace(text) { m ->
        val token = "@@C_${codeTokens.size}@@"
        codeTokens.add("<code>${escapeHtml(m.groupValues[1])}</code>")
        token
    }
    val mathTokens = mutableListOf<String>()
    val protectedMath = HTML_MATH_RE.replace(protectedCode) { m ->
        val token = "@@M_${mathTokens.size}@@"
        mathTokens.add(m.value)
        token
    }
    var s = escapeHtml(protectedMath)
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
    s = HTML_MATH_PH_RE.replace(s) { m -> escapeHtml(mathTokens.getOrElse(m.groupValues[1].toInt()) { "" }) }
    s = HTML_CODE_PH_RE.replace(s) { m -> codeTokens.getOrElse(m.groupValues[1].toInt()) { "" } }
    return s
}

internal fun estimateVisibleTextLen(raw: String): Int {
    val stripped = raw
        .replace(EST_FONT_TAG_RE, "$1")
        .replace(EST_TEX_CMD_ARG_RE, "$1")
        .replace(EST_TEX_CMD_RE, "x")
        .replace(EST_MARKUP_CHARS_RE, "")
        .replace("\\(", "")
        .replace("\\)", "")
    return stripped.trim().length.coerceAtLeast(1)
}

internal fun tableToMarkdown(table: MdBlock.Table): String = buildString {
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

/**
 * Line-oriented Markdown block parser that properly handles variable-length
 * fenced code blocks (```` ``` ````, ````` ```` `````, `~~~`) without splitting
 * on inline triple backticks.
 */
internal fun parseMarkdown(raw: String): List<MdBlock> {
    if (raw.isEmpty()) return emptyList()
    val out = mutableListOf<MdBlock>()
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
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

        // Fenced code blocks: ```lang or ~~~lang (at least 3 fence chars, closed by >= same char count)
        val fenceOpen = MD_FENCE_OPEN_RE.matchEntire(line)
        if (fenceOpen != null) {
            val marker = fenceOpen.groupValues[1]
            val fenceChar = marker[0]
            val fenceLen = marker.length
            val lang = fenceOpen.groupValues[2].trim().split(SPLIT_WS_RE).firstOrNull().orEmpty().take(24)
            flushPara()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size) {
                val closeTrim = lines[i].trim()
                val isClosingFence = closeTrim.length >= fenceLen && closeTrim.all { it == fenceChar }
                if (isClosingFence) {
                    i++
                    break
                }
                codeLines.add(lines[i])
                i++
            }
            out.add(MdBlock.Code(lang, codeLines.joinToString("\n")))
            continue
        }

        if (trimmed.isEmpty()) {
            flushPara(); i++; continue
        }
        if (trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4 &&
            trimmed.indexOf("$$", 2) == trimmed.length - 2
        ) {
            flushPara()
            out.add(MdBlock.DisplayMath(trimmed.drop(2).dropLast(2).trim()))
            i++; continue
        }
        if (trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length > 4 &&
            trimmed.indexOf("\\]", 2) == trimmed.length - 2
        ) {
            flushPara()
            out.add(MdBlock.DisplayMath(trimmed.drop(2).dropLast(2).trim()))
            i++; continue
        }
        if (trimmed == "$$" || trimmed == "\\[" ||
            (trimmed.startsWith("$$") && !trimmed.drop(2).contains("$$")) ||
            (trimmed.startsWith("\\[") && !trimmed.drop(2).contains("\\]"))
        ) {
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
    return out
}

internal fun splitTableRow(line: String, expectedCols: Int? = null): List<String> {
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
        val l = it.startsWith(":")
        val r = it.endsWith(":")
        when {
            l && r -> TextAlign.Center
            r -> TextAlign.End
            else -> TextAlign.Start
        }
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
