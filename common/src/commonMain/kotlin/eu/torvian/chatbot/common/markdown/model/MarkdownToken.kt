package eu.torvian.chatbot.common.markdown.model

/**
 * Token slice of markdown source text for editor-style syntax highlighting.
 *
 * Ranges refer to the original source string to avoid copying text and enable
 * best-effort tokenization for incomplete input.
 *
 * @property kind classification for mapping to styles later.
 * @property start inclusive start offset in the source string.
 * @property endExclusive exclusive end offset in the source string.
 */
data class MarkdownToken(
    val kind: MarkdownTokenKind,
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) {
            "start must be >= 0, but was $start"
        }
        require(endExclusive >= start) {
            "endExclusive must be >= start (start=$start, endExclusive=$endExclusive)"
        }
    }
}
