package eu.torvian.chatbot.common.markdown.lexer

/**
 * Slice metadata for a single source line, including its newline span.
 *
 * @property start inclusive start offset for the line content.
 * @property contentEnd exclusive end offset for the line content, excluding newline chars.
 * @property newlineEnd exclusive end offset including any trailing newline chars.
 */
internal data class LineInfo(
    val start: Int,
    val contentEnd: Int,
    val newlineEnd: Int,
)

/**
 * Local offsets for a fenced-code marker within a line.
 *
 * @property start inclusive start offset within the line.
 * @property endExclusive exclusive end offset within the line.
 */
internal data class FenceMatch(
    val start: Int,
    val endExclusive: Int,
)

/**
 * Results for table row handling to keep minimal table-region state.
 *
 * @property handled whether the line was tokenized as a table row.
 * @property inTableRegion whether subsequent lines should be treated as table rows.
 */
internal data class TableHandlingResult(
    val handled: Boolean,
    val inTableRegion: Boolean,
)

