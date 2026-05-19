package eu.torvian.chatbot.app.compose.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import eu.torvian.chatbot.common.markdown.model.MarkdownTokenKind

/**
 * Maps markdown token kinds to span styles for source highlighting.
 */
fun interface MarkdownStyleMapper {
    /**
     * Resolves a span style for the provided token kind.
     *
     * @param kind classification for the source token.
     * @return span style to apply, or null when no extra styling is desired.
     */
    fun styleFor(kind: MarkdownTokenKind): SpanStyle?
}

/**
 * Style palette for markdown source highlighting.
 *
 * @property defaultStyle fallback styling for tokens without a dedicated entry.
 * @property stylesByKind per-token overrides that are applied on top of the base text style.
 */
data class MarkdownHighlightingStyles(
    val defaultStyle: SpanStyle,
    val stylesByKind: Map<MarkdownTokenKind, SpanStyle>,
) : MarkdownStyleMapper {
    override fun styleFor(kind: MarkdownTokenKind): SpanStyle = stylesByKind[kind] ?: defaultStyle
}

/**
 * Builds a theme-aware markdown highlighting palette.
 *
 * @param baseStyle text style that the highlighted spans will build upon.
 * @param color optional override for the default text color.
 * @return a style mapper that provides span styles for markdown token kinds.
 */
@Composable
fun rememberMarkdownHighlightingStyles(
    baseStyle: TextStyle,
    color: Color? = null,
): MarkdownHighlightingStyles {
    val colorScheme = MaterialTheme.colorScheme
    val baseColor = color
        ?: baseStyle.color.takeIf { it != Color.Unspecified }
        ?: colorScheme.onSurface
    return remember(baseStyle, color, colorScheme) {
        val subdued = colorScheme.onSurfaceVariant
        val fenceBackground = colorScheme.surfaceVariant.copy(alpha = 0.6f)
        val map = buildMap {
            put(MarkdownTokenKind.PlainText, SpanStyle(color = baseColor))
            put(MarkdownTokenKind.HeadingMarker, SpanStyle(color = colorScheme.primary))
            put(
                MarkdownTokenKind.HeadingText,
                SpanStyle(color = colorScheme.primary, fontWeight = FontWeight.SemiBold),
            )
            put(MarkdownTokenKind.ListMarker, SpanStyle(color = colorScheme.secondary))
            put(MarkdownTokenKind.OrderedListMarker, SpanStyle(color = colorScheme.secondary))
            put(MarkdownTokenKind.BlockquoteMarker, SpanStyle(color = subdued))
            put(
                MarkdownTokenKind.BlockquoteText,
                SpanStyle(color = subdued, fontStyle = FontStyle.Italic),
            )
            put(MarkdownTokenKind.EmphasisDelimiter, SpanStyle(color = subdued))
            put(
                MarkdownTokenKind.EmphasisText,
                SpanStyle(color = baseColor, fontStyle = FontStyle.Italic),
            )
            put(MarkdownTokenKind.StrongDelimiter, SpanStyle(color = subdued))
            put(
                MarkdownTokenKind.StrongText,
                SpanStyle(color = baseColor, fontWeight = FontWeight.Bold),
            )
            put(MarkdownTokenKind.StrikeDelimiter, SpanStyle(color = subdued))
            put(
                MarkdownTokenKind.StrikeText,
                SpanStyle(color = baseColor, textDecoration = TextDecoration.LineThrough),
            )
            put(
                MarkdownTokenKind.InlineCodeDelimiter,
                SpanStyle(color = colorScheme.tertiary, fontFamily = FontFamily.Monospace),
            )
            put(
                MarkdownTokenKind.InlineCodeText,
                SpanStyle(
                    color = colorScheme.tertiary,
                    fontFamily = FontFamily.Monospace,
                    background = fenceBackground,
                ),
            )
            put(MarkdownTokenKind.CodeFence, SpanStyle(color = subdued))
            put(
                MarkdownTokenKind.CodeFenceLanguage,
                SpanStyle(color = colorScheme.tertiary, fontStyle = FontStyle.Italic),
            )
            put(
                MarkdownTokenKind.CodeBlockText,
                SpanStyle(color = colorScheme.tertiary, fontFamily = FontFamily.Monospace),
            )
            put(MarkdownTokenKind.LinkTextDelimiter, SpanStyle(color = subdued))
            put(
                MarkdownTokenKind.LinkText,
                SpanStyle(color = colorScheme.primary, textDecoration = TextDecoration.Underline),
            )
            put(MarkdownTokenKind.LinkUrlDelimiter, SpanStyle(color = subdued))
            put(MarkdownTokenKind.LinkUrl, SpanStyle(color = colorScheme.tertiary))
            put(MarkdownTokenKind.ImageMarker, SpanStyle(color = colorScheme.tertiary))
            put(MarkdownTokenKind.TablePipe, SpanStyle(color = subdued))
            put(
                MarkdownTokenKind.TableHeaderText,
                SpanStyle(color = colorScheme.primary, fontWeight = FontWeight.Medium),
            )
            put(MarkdownTokenKind.TableCellText, SpanStyle(color = baseColor))
            put(
                MarkdownTokenKind.TableDelimiterRow,
                SpanStyle(color = subdued, fontStyle = FontStyle.Italic),
            )
            put(MarkdownTokenKind.HorizontalRule, SpanStyle(color = subdued))
            put(MarkdownTokenKind.EscapeSequence, SpanStyle(color = colorScheme.tertiary))
        }
        MarkdownHighlightingStyles(
            defaultStyle = SpanStyle(color = baseColor),
            stylesByKind = map,
        )
    }
}

