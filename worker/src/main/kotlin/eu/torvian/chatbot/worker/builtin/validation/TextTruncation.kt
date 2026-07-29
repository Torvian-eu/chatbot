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
 * Formats a standard truncation notice string indicating how much content was shown and how to request more.
 *
 * @param linesShown Number of lines shown in the output.
 * @param bytesShown Number of bytes shown in the output.
 * @param instruction The guidance text explaining how to request more content.
 * @return The formatted truncation notice string.
 */
internal fun formatTruncationNotice(
    linesShown: Int,
    bytesShown: Int,
    instruction: String = "Increase 'maxLines'/'maxBytes' to read further.",
): String {
    return "\n\n[Output truncated: showing first $linesShown lines / $bytesShown bytes. ${instruction.trim()}]"
}

/**
 * Builds a single concise header line describing the line range returned from [label].
 *
 * Format: `=== <label> (lines:<range> of <totalLines>) ===`
 *
 * @param label The target path or URL string.
 * @param startIdx 0-based inclusive start index of the slice.
 * @param endIdx 0-based exclusive end index of the slice.
 * @param totalLines Total number of lines in the source document.
 * @return The formatted range header line.
 */
internal fun buildRangeHeader(
    label: String,
    startIdx: Int,
    endIdx: Int,
    totalLines: Int,
): String {
    val count = endIdx - startIdx
    val range = when {
        count <= 0 -> "none"
        count == 1 -> "${startIdx + 1}"
        else -> "${startIdx + 1}-$endIdx"
    }
    return "=== $label (lines:$range of $totalLines) ==="
}
