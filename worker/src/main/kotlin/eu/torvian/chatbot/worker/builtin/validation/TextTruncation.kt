package eu.torvian.chatbot.worker.builtin.validation

/**
 * Represents the result of truncating text to maximum lines and bytes limits.
 *
 * @property text The truncated output text string.
 * @property linesShown The number of lines contained in the truncated text.
 * @property bytesShown The exact UTF-8 byte count of the truncated text.
 * @property isTruncated True if either line or byte truncation occurred.
 */
internal data class TruncationResult(
    val text: String,
    val linesShown: Int,
    val bytesShown: Int,
    val isTruncated: Boolean,
)

/**
 * Truncates [text] to at most [maxLines] lines and at most [maxBytes] UTF-8 bytes.
 *
 * @param text The source text string to truncate.
 * @param maxLines Maximum number of lines to retain.
 * @param maxBytes Maximum number of UTF-8 bytes to retain.
 * @return A [TruncationResult] containing the truncated text and metrics.
 */
internal fun truncateLinesAndBytes(
    text: String,
    maxLines: Int,
    maxBytes: Int,
): TruncationResult {
    if (text.isEmpty()) {
        return TruncationResult(text = "", linesShown = 0, bytesShown = 0, isTruncated = false)
    }

    val lines = text.lines()
    var isTruncated = false
    val cappedLines = if (lines.size > maxLines) {
        isTruncated = true
        lines.take(maxLines)
    } else {
        lines
    }

    val rawBody = cappedLines.joinToString("\n")
    val (body, bytesShown) = truncateBytes(rawBody, maxBytes)
    if (body.length < rawBody.length) {
        isTruncated = true
    }

    val linesShown = if (body.isEmpty()) 0 else body.lines().size
    return TruncationResult(
        text = body,
        linesShown = linesShown,
        bytesShown = bytesShown,
        isTruncated = isTruncated,
    )
}

/**
 * Formats a standard truncation notice string indicating how much content was shown.
 *
 * @param linesShown Number of lines shown in the output.
 * @param bytesShown Number of bytes shown in the output.
 * @param extraHint Optional extra hint text to include in the notice before instructions.
 * @return The formatted truncation notice string.
 */
internal fun formatTruncationNotice(
    linesShown: Int,
    bytesShown: Int,
    extraHint: String? = null,
): String {
    val hintSuffix = if (!extraHint.isNullOrBlank()) " ${extraHint.trim()}" else ""
    return "\n\n[Output truncated: showing first $linesShown lines / $bytesShown bytes.$hintSuffix Increase 'maxLines'/'maxBytes' to read further.]"
}
