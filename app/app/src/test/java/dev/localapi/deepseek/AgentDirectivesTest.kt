package dev.localapi.deepseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDirectivesTest {

    @Test
    fun extractsAndStripsAgentDirectives() {
        val reply = "Проверяю файлы.\n`[[EXEC: ls -la /workspace]]`\n[[SEND: /workspace/out.png]]"
        assertEquals("ls -la /workspace", AgentDirectives.extractExec(reply))
        assertEquals(listOf("out.png"), AgentDirectives.extractMedia(reply))
        assertEquals("Проверяю файлы.", AgentDirectives.stripDirectives(reply))
    }

    @Test
    fun parsesBrowserDirectiveWithAndWithoutArg() {
        assertEquals("open" to "https://example.com", AgentDirectives.extractBrowser("[[BROWSER: open|https://example.com]]"))
        assertEquals("screenshot" to "", AgentDirectives.extractBrowser("[[BROWSER: screenshot]]"))
        assertNull(AgentDirectives.extractBrowser("Обычный текст"))
    }

    @Test
    fun parsesToolResultEnvelopeCleanly() {
        val raw = "[TOOL_RESULT]\nexit code: 0\nhello world\n\nПродолжай. Если нужна ещё команда..."
        val (out, code) = AgentDirectives.parseToolResult(raw)
        assertEquals("hello world", out)
        assertEquals("0", code)
    }

    @Test
    fun estimatesTokensForCyrillicAndLatin() {
        assertTrue(AgentDirectives.estimateTokens("Привет, мир!") > 0)
        assertTrue(AgentDirectives.estimateTokens("hello world") > 0)
        assertEquals(0, AgentDirectives.estimateTokens(""))
    }
}
