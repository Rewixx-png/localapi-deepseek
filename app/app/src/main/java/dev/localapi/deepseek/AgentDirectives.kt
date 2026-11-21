package dev.localapi.deepseek

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Pure Kotlin helpers for agent tool directives, token estimation, and display
 * formatting. Kept Android-free so every parser rule is covered by JUnit tests.
 */
object AgentDirectives {
    private val WRAPPED_DIRECTIVE_RE =
        Regex("`\\s*\\[\\[(?:EXEC|SEND|BROWSER|BROWSE):[\\s\\S]*?\\]\\]\\s*`", RegexOption.IGNORE_CASE)
    private val DIRECTIVE_RE =
        Regex("\\[\\[(?:EXEC|SEND|BROWSER|BROWSE):[\\s\\S]*?\\]\\]", RegexOption.IGNORE_CASE)
    private val EXEC_RE = Regex("\\[\\[EXEC:\\s*([\\s\\S]*?)\\]\\]", RegexOption.IGNORE_CASE)
    private val BROWSE_RE = Regex("\\[\\[BROWSE:\\s*([\\s\\S]*?)\\]\\]", RegexOption.IGNORE_CASE)
    private val BROWSER_RE = Regex("\\[\\[BROWSER:\\s*([\\s\\S]*?)\\]\\]", RegexOption.IGNORE_CASE)
    private val SEND_RE = Regex("\\[\\[SEND:\\s*([^\\]\\n]+)\\]\\]", RegexOption.IGNORE_CASE)
    private val EXIT_CODE_RE = Regex("^exit code:\\s*([^\\n]*)\\n?", RegexOption.IGNORE_CASE)
    private val WORKSPACE_PREFIX_RE = Regex("^/workspace/", RegexOption.IGNORE_CASE)
    private val RU_LOCALE = Locale.forLanguageTag("ru")

    fun stripDirectives(text: String): String =
        text.replace(WRAPPED_DIRECTIVE_RE, "").replace(DIRECTIVE_RE, "").trim()

    fun extractExec(text: String): String =
        EXEC_RE.find(text)?.groupValues?.get(1)?.trim().orEmpty()

    fun extractBrowse(text: String): String =
        BROWSE_RE.find(text)?.groupValues?.get(1)?.trim()?.take(512).orEmpty()

    fun extractBrowser(text: String): Pair<String, String>? {
        val raw = BROWSER_RE.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val idx = raw.indexOf('|')
        return if (idx < 0) {
            raw.lowercase(Locale.US) to ""
        } else {
            raw.substring(0, idx).trim().lowercase(Locale.US) to raw.substring(idx + 1).trim()
        }
    }

    fun extractMedia(text: String): List<String> =
        SEND_RE.findAll(text)
            .map { it.groupValues[1].trim().replace(WORKSPACE_PREFIX_RE, "").trimStart('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    fun parseToolResult(content: String): Pair<String, String> {
        var text = content.removePrefix("[TOOL_RESULT]").trimStart()
        var code = "?"
        val m = EXIT_CODE_RE.find(text)
        if (m != null) {
            code = m.groupValues[1].trim().ifEmpty { "?" }
            text = text.removeRange(m.range)
        }
        val cut = text.indexOf("\n\nПродолжай.")
        if (cut >= 0) text = text.substring(0, cut)
        return text.trimEnd().ifEmpty { "(пусто)" } to code
    }

    fun browserBrowseUrl(query: String): String =
        if (query.startsWith("http://", true) || query.startsWith("https://", true)) query
        else AppConfig.DEFAULT_SEARCH_URL_PREFIX + URLEncoder.encode(query, "UTF-8")

    fun estimateTokens(text: String): Int {
        var cyr = 0
        var lat = 0
        var other = 0
        text.forEach { c ->
            when {
                c in '\u0400'..'\u04FF' -> cyr++
                c.code <= 0x7F -> lat++
                else -> other++
            }
        }
        return ceil(cyr / 2.6 + lat / 4.0 + other / 3.0).toInt()
    }

    fun formatDuration(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000.0
        return when {
            s < 10 -> String.format(RU_LOCALE, "%.1f с", s)
            s < 60 -> "${s.roundToLong()} с"
            else -> "${(s / 60).toInt()} мин ${(s % 60).roundToLong()} с"
        }
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> String.format(RU_LOCALE, "%.1f КБ", bytes / 1024.0)
        else -> String.format(RU_LOCALE, "%.1f МБ", bytes / (1024.0 * 1024.0))
    }

    fun formatTimeShort(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    fun timeAgo(ts: Long): String {
        val diff = (System.currentTimeMillis() - ts).coerceAtLeast(0L)
        val min = diff / 60_000
        if (min < 1) return "только что"
        if (min < 60) return "$min мин назад"
        val h = min / 60
        if (h < 24) return "$h ч назад"
        val d = h / 24
        return if (d < 7) "$d дн назад" else SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(ts))
    }
}
