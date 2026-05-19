package eu.torvian.chatbot.app.compose.markdown

import androidx.compose.ui.text.AnnotatedString
import eu.torvian.chatbot.common.markdown.lexer.MarkdownTokenizer
import eu.torvian.chatbot.common.markdown.model.MarkdownToken

/**
 * Builds highlighted [AnnotatedString] instances from markdown source text.
 *
 * @property tokenizer tokenizer used to create source-based token ranges.
 * @property styleMapper mapper that resolves span styles for token kinds.
 */
class MarkdownAnnotatedStringBuilder(
    private val tokenizer: MarkdownTokenizer,
    private val styleMapper: MarkdownStyleMapper,
) {
    /**
     * Converts markdown source text into an [AnnotatedString] with syntax spans.
     *
     * @param markdown raw markdown input to highlight.
     * @return annotated string containing the original text and span ranges.
     */
    fun build(markdown: String): AnnotatedString {
        if (markdown.isEmpty()) {
            return AnnotatedString("")
        }
        val tokens = tokenizer.tokenize(markdown)
        val builder = AnnotatedString.Builder(markdown)
        applyTokenStyles(builder, markdown.length, tokens)
        return builder.toAnnotatedString()
    }

    /**
     * Applies the mapped span styles onto the annotated string builder.
     *
     * @param builder target builder that already contains the original markdown text.
     * @param markdownLength length of the original markdown input.
     * @param tokens token ranges referencing the original markdown input.
     */
    private fun applyTokenStyles(
        builder: AnnotatedString.Builder,
        markdownLength: Int,
        tokens: List<MarkdownToken>,
    ) {
        for (token in tokens) {
            val style = styleMapper.styleFor(token.kind) ?: continue
            // Clamp to guard against best-effort tokens that may be out of bounds.
            val start = token.start.coerceIn(0, markdownLength)
            val end = token.endExclusive.coerceIn(start, markdownLength)
            if (start == end) {
                continue
            }
            builder.addStyle(style, start, end)
        }
    }
}

