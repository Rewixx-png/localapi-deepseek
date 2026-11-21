package dev.localapi.deepseek.ui

import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun fencedCodeBlocksHandleVariableLengthsAndInlineTripleBackticks() {
        val md = """
            Use ```inline``` inside a paragraph.

            ````markdown
            Inside outer fence:
            ```kotlin
            val x = 1
            ```
            ````

            ~~~python
            print("ok")
            ~~~
        """.trimIndent()

        val blocks = parseMarkdown(md)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals("Use ```inline``` inside a paragraph.", (blocks[0] as MdBlock.Paragraph).text)

        val outer = blocks[1] as MdBlock.Code
        assertEquals("markdown", outer.lang)
        assertTrue(outer.code.contains("```kotlin"))
        assertTrue(outer.code.contains("val x = 1"))

        val py = blocks[2] as MdBlock.Code
        assertEquals("python", py.lang)
        assertEquals("print(\"ok\")", py.code)
    }

    @Test
    fun inlineCodeProtectsLatexDelimitersAndEscapesHtml() {
        val html = inlineMarkdownToSafeHtml("Code `\$x+y\$ <script>` and math \$a^2+b^2\$")
        assertTrue(html.contains("<code>\$x+y\$ &lt;script&gt;</code>"))
        assertTrue(html.contains("\$a^2+b^2\$"))
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun containsLatexMathIgnoresMathInsideInlineCode() {
        assertFalse(containsLatexMath("Run `echo \$HOME` and `\$x^2\$`"))
        assertTrue(containsLatexMath("Formula \$x^2 + 1\$ here"))
        assertTrue(containsLatexMath("Block \\(a+b\\)"))
    }

    @Test
    fun gfmTableParsesAlignmentsAndEscapedPipes() {
        val md = """
            | Name | Value | Score |
            | :--- | :---: | ---: |
            | a\|b | `c|d` | 10 |
        """.trimIndent()

        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val tbl = blocks[0] as MdBlock.Table
        assertEquals(listOf("Name", "Value", "Score"), tbl.headers)
        assertEquals(listOf(TextAlign.Start, TextAlign.Center, TextAlign.End), tbl.aligns)
        assertEquals(listOf(listOf("a|b", "`c|d`", "10")), tbl.rows)
    }
}
