package eu.torvian.chatbot.app.compose.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import eu.torvian.chatbot.common.markdown.lexer.DefaultMarkdownTokenizer
import eu.torvian.chatbot.common.markdown.lexer.MarkdownTokenizer

/**
 * Renders markdown source text using token-based syntax highlighting.
 *
 * @param markdown raw markdown text to highlight.
 * @param modifier modifier applied to the [Text] composable.
 * @param style base text style applied to the output text.
 * @param color optional override for the default text color.
 * @param tokenizer tokenizer used to generate markdown tokens.
 * @param styleMapper optional mapper that controls token kind styling.
 * @param extraSpanStyles additional spans layered on top of markdown syntax highlighting.
 * @param maxLines maximum number of lines for the rendered text.
 * @param overflow overflow behavior when the text exceeds the layout bounds.
 * @param onTextLayout optional callback invoked with the final text layout result.
 */
@Composable
fun MarkdownHighlightedText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color? = null,
    tokenizer: MarkdownTokenizer = DefaultMarkdownTokenizer,
    styleMapper: MarkdownStyleMapper? = null,
    extraSpanStyles: List<AnnotatedString.Range<SpanStyle>> = emptyList(),
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val resolvedStyleMapper = styleMapper ?: rememberMarkdownHighlightingStyles(style, color)
    val builder = remember(tokenizer, resolvedStyleMapper) {
        MarkdownAnnotatedStringBuilder(tokenizer, resolvedStyleMapper)
    }
    val annotated = remember(markdown, builder, extraSpanStyles) {
        builder.build(markdown, extraSpanStyles)
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color ?: Color.Unspecified,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = onTextLayout,
    )
}

