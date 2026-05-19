package eu.torvian.chatbot.common.markdown.lexer

import eu.torvian.chatbot.common.markdown.model.MarkdownToken
import eu.torvian.chatbot.common.markdown.model.MarkdownTokenKind

/**
 * Builds line slices with content and newline offsets for line-oriented scanning.
 *
 * @param markdown full markdown source text.
 * @return ordered list of line metadata spanning the source.
 */
internal fun buildLines(markdown: String): List<LineInfo> {
    val lines = ArrayList<LineInfo>()
    var index = 0

    while (index < markdown.length) {
        val lineBreakIndex = markdown.indexOf('\n', index)
        val lineEnd = if (lineBreakIndex == -1) markdown.length else lineBreakIndex
        val contentEnd = if (lineEnd > index && markdown[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
        val newlineEnd = if (lineBreakIndex == -1) lineEnd else lineEnd + 1

        lines.add(LineInfo(start = index, contentEnd = contentEnd, newlineEnd = newlineEnd))
        index = newlineEnd
    }

    return lines
}

/**
 * Adds a token if the range is non-empty, preserving source offsets.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param kind classification for the emitted token.
 * @param start inclusive start offset in the source string.
 * @param endExclusive exclusive end offset in the source string.
 */
internal fun addToken(
    tokens: MutableList<MarkdownToken>,
    kind: MarkdownTokenKind,
    start: Int,
    endExclusive: Int,
) {
    if (start >= endExclusive) {
        return
    }

    tokens.add(MarkdownToken(kind = kind, start = start, endExclusive = endExclusive))
}

/**
 * Finds the first non-space/tab character starting at the provided offset.
 *
 * @param text line text to scan.
 * @param start offset to begin scanning within the line.
 * @return index of the first non-whitespace character, or -1 if none.
 */
internal fun firstNonWhitespaceIndex(text: String, start: Int): Int {
    for (index in start until text.length) {
        val char = text[index]
        if (char != ' ' && char != '\t') {
            return index
        }
    }

    return -1
}

/**
 * Detects a backtick fence marker in the provided line.
 *
 * @param lineText line content without newline characters.
 * @return fence match offsets within the line, or null if none is present.
 */
internal fun findFenceMarker(lineText: String): FenceMatch? {
    val markerStart = firstNonWhitespaceIndex(lineText, 0)
    if (markerStart == -1) {
        return null
    }
    var markerEnd = markerStart
    while (markerEnd < lineText.length && lineText[markerEnd] == '`') {
        markerEnd += 1
    }
    return if (markerEnd - markerStart >= 3) {
        FenceMatch(start = markerStart, endExclusive = markerEnd)
    } else {
        null
    }
}

/**
 * Determines whether a line is a horizontal rule candidate.
 *
 * @param lineText line content without newline characters.
 * @return true when the line clearly represents a horizontal rule.
 */
internal fun isHorizontalRuleLine(lineText: String): Boolean {
    val compact = lineText.filterNot { it == ' ' || it == '\t' }
    if (compact.length < 3) {
        return false
    }
    val marker = compact[0]
    if (marker != '-' && marker != '*' && marker != '_') {
        return false
    }
    return compact.all { it == marker }
}

/**
 * Determines whether a line resembles a table delimiter row.
 *
 * @param lineText line content without newline characters.
 * @return true when the line resembles a header delimiter row.
 */
internal fun isTableDelimiterRow(lineText: String): Boolean {
    if (!lineText.contains('|')) {
        return false
    }
    val trimmed = lineText.trim()
    if (trimmed.isEmpty()) {
        return false
    }

    var dashCount = 0
    for (char in trimmed) {
        when (char) {
            '-' -> dashCount += 1
            ':', '|' -> Unit
            else -> return false
        }
    }

    return dashCount >= 3
}

/**
 * Determines whether a line resembles a table delimiter row based on the source string.
 *
 * @param markdown full markdown source text.
 * @param line line metadata for the row to inspect.
 * @return true when the line resembles a header delimiter row.
 */
internal fun isTableDelimiterRow(markdown: String, line: LineInfo): Boolean {
    val lineText = markdown.substring(line.start, line.contentEnd)
    return isTableDelimiterRow(lineText)
}

