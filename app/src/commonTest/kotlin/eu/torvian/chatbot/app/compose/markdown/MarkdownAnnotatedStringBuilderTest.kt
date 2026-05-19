package eu.torvian.chatbot.app.compose.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import eu.torvian.chatbot.common.markdown.lexer.MarkdownTokenizer
import eu.torvian.chatbot.common.markdown.model.MarkdownToken
import eu.torvian.chatbot.common.markdown.model.MarkdownTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that markdown token ranges become annotated string span ranges.
 */
class MarkdownAnnotatedStringBuilderTest {
    /**
     * Ensures token spans are applied without altering the source text.
     */
    @Test
    fun build_appliesTokenRangesToAnnotatedString() {
        val markdown = "abc"
        val tokenizer = FixedTokenTokenizer(
            listOf(
                MarkdownToken(MarkdownTokenKind.PlainText, 0, 1),
                MarkdownToken(MarkdownTokenKind.HeadingMarker, 1, 2),
                MarkdownToken(MarkdownTokenKind.HeadingText, 2, 3),
            ),
        )
        val styleMapper = MarkdownStyleMapper { SpanStyle(color = Color.Red) }
        val builder = MarkdownAnnotatedStringBuilder(tokenizer, styleMapper)

        val annotated = builder.build(markdown)

        assertEquals(markdown, annotated.text)
        assertEquals(3, annotated.spanStyles.size)
        assertEquals(0, annotated.spanStyles[0].start)
        assertEquals(1, annotated.spanStyles[0].end)
        assertEquals(1, annotated.spanStyles[1].start)
        assertEquals(2, annotated.spanStyles[1].end)
        assertEquals(2, annotated.spanStyles[2].start)
        assertEquals(3, annotated.spanStyles[2].end)
    }
}

/**
 * Tokenizer stub that returns predetermined tokens for deterministic tests.
 *
 * @property tokens token list to return for every input string.
 */
private class FixedTokenTokenizer(
    private val tokens: List<MarkdownToken>,
) : MarkdownTokenizer {
    /**
     * Returns the fixed token list, ignoring the provided input string.
     *
     * @param markdown unused markdown input required by the interface.
     * @return the fixed token list for this test instance.
     */
    override fun tokenize(markdown: String): List<MarkdownToken> = tokens
}

