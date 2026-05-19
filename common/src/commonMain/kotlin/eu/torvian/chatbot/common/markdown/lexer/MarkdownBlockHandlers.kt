package eu.torvian.chatbot.common.markdown.lexer

import eu.torvian.chatbot.common.markdown.model.MarkdownToken
import eu.torvian.chatbot.common.markdown.model.MarkdownTokenKind

/**
 * Handles an opening fence line and emits fence/language tokens when found.
 *
 * Newlines after the opening fence are emitted as code-block text to keep the
 * fence and code content separated consistently.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param hasNewline true when a newline suffix exists for the line.
 * @param newlineStart inclusive start offset of the newline span.
 * @param newlineEnd exclusive end offset of the newline span.
 * @return true when the line opens a fenced code block.
 */
internal fun handleFenceOpening(
    tokens: MutableList<MarkdownToken>,
    lineText: String,
    lineStart: Int,
    hasNewline: Boolean,
    newlineStart: Int,
    newlineEnd: Int,
): Boolean {
    val openingFence = findFenceMarker(lineText) ?: return false

    if (openingFence.start > 0) {
        addToken(tokens, MarkdownTokenKind.PlainText, lineStart, lineStart + openingFence.start)
    }
    addToken(
        tokens,
        MarkdownTokenKind.CodeFence,
        lineStart + openingFence.start,
        lineStart + openingFence.endExclusive,
    )
    val afterFence = openingFence.endExclusive
    if (afterFence < lineText.length) {
        val infoStart = firstNonWhitespaceIndex(lineText, afterFence)
        if (infoStart == -1) {
            addToken(tokens, MarkdownTokenKind.PlainText, lineStart + afterFence, lineStart + lineText.length)
        } else {
            if (infoStart > afterFence) {
                addToken(tokens, MarkdownTokenKind.PlainText, lineStart + afterFence, lineStart + infoStart)
            }
            addToken(tokens, MarkdownTokenKind.CodeFenceLanguage, lineStart + infoStart, lineStart + lineText.length)
        }
    }
    if (hasNewline) {
        addToken(tokens, MarkdownTokenKind.CodeBlockText, newlineStart, newlineEnd)
    }

    return true
}

/**
 * Handles a line while inside a fenced code block and returns the updated state.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param hasNewline true when a newline suffix exists for the line.
 * @param newlineStart inclusive start offset of the newline span.
 * @param newlineEnd exclusive end offset of the newline span.
 * @return true when still inside a fenced code block after processing.
 */
internal fun handleFenceContinuation(
    tokens: MutableList<MarkdownToken>,
    lineText: String,
    lineStart: Int,
    hasNewline: Boolean,
    newlineStart: Int,
    newlineEnd: Int,
): Boolean {
    val fenceMatch = findFenceMarker(lineText)
    if (fenceMatch != null) {
        if (fenceMatch.start > 0) {
            addToken(tokens, MarkdownTokenKind.CodeBlockText, lineStart, lineStart + fenceMatch.start)
        }
        addToken(
            tokens,
            MarkdownTokenKind.CodeFence,
            lineStart + fenceMatch.start,
            lineStart + fenceMatch.endExclusive,
        )
        if (fenceMatch.endExclusive < lineText.length) {
            addToken(tokens, MarkdownTokenKind.PlainText, lineStart + fenceMatch.endExclusive, lineStart + lineText.length)
        }
        if (hasNewline) {
            addToken(tokens, MarkdownTokenKind.CodeBlockText, newlineStart, newlineEnd)
        }
        return false
    }

    addToken(tokens, MarkdownTokenKind.CodeBlockText, lineStart, lineStart + lineText.length)
    if (hasNewline) {
        addToken(tokens, MarkdownTokenKind.CodeBlockText, newlineStart, newlineEnd)
    }
    return true
}

/**
 * Emits heading tokens when the line is a valid ATX heading.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param hasNewline true when a newline suffix exists for the line.
 * @param newlineStart inclusive start offset of the newline span.
 * @param newlineEnd exclusive end offset of the newline span.
 * @return true when the line was handled as a heading.
 */
internal fun handleHeadingLine(
    tokens: MutableList<MarkdownToken>,
    lineText: String,
    lineStart: Int,
    hasNewline: Boolean,
    newlineStart: Int,
    newlineEnd: Int,
): Boolean {
    val firstNonWhitespace = firstNonWhitespaceIndex(lineText, 0)
    if (firstNonWhitespace == -1 || lineText[firstNonWhitespace] != '#') {
        return false
    }

    var markerEnd = firstNonWhitespace
    while (markerEnd < lineText.length && lineText[markerEnd] == '#' && markerEnd - firstNonWhitespace < 6) {
        markerEnd += 1
    }
    val markerCount = markerEnd - firstNonWhitespace
    if (markerCount !in 1..6) {
        return false
    }
    if (markerEnd < lineText.length && !lineText[markerEnd].isWhitespace()) {
        return false
    }

    if (firstNonWhitespace > 0) {
        addToken(tokens, MarkdownTokenKind.PlainText, lineStart, lineStart + firstNonWhitespace)
    }
    addToken(tokens, MarkdownTokenKind.HeadingMarker, lineStart + firstNonWhitespace, lineStart + markerEnd)
    if (markerEnd < lineText.length) {
        emitInlineTokens(
            tokens = tokens,
            baseKind = MarkdownTokenKind.HeadingText,
            lineText = lineText,
            lineStart = lineStart,
            rangeStart = lineStart + markerEnd,
            rangeEndExclusive = lineStart + lineText.length,
        )
    }
    if (hasNewline) {
        addToken(tokens, MarkdownTokenKind.PlainText, newlineStart, newlineEnd)
    }
    return true
}

/**
 * Emits blockquote tokens for lines starting with the blockquote marker.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param hasNewline true when a newline suffix exists for the line.
 * @param newlineStart inclusive start offset of the newline span.
 * @param newlineEnd exclusive end offset of the newline span.
 * @return true when the line was handled as a blockquote.
 */
internal fun handleBlockquoteLine(
    tokens: MutableList<MarkdownToken>,
    lineText: String,
    lineStart: Int,
    hasNewline: Boolean,
    newlineStart: Int,
    newlineEnd: Int,
): Boolean {
    val firstNonWhitespace = firstNonWhitespaceIndex(lineText, 0)
    if (firstNonWhitespace == -1 || lineText[firstNonWhitespace] != '>') {
        return false
    }

    if (firstNonWhitespace > 0) {
        addToken(tokens, MarkdownTokenKind.PlainText, lineStart, lineStart + firstNonWhitespace)
    }
    addToken(tokens, MarkdownTokenKind.BlockquoteMarker, lineStart + firstNonWhitespace, lineStart + firstNonWhitespace + 1)
    if (firstNonWhitespace + 1 < lineText.length) {
        emitInlineTokens(
            tokens = tokens,
            baseKind = MarkdownTokenKind.BlockquoteText,
            lineText = lineText,
            lineStart = lineStart,
            rangeStart = lineStart + firstNonWhitespace + 1,
            rangeEndExclusive = lineStart + lineText.length,
        )
    }
    if (hasNewline) {
        addToken(tokens, MarkdownTokenKind.PlainText, newlineStart, newlineEnd)
    }
    return true
}

/**
 * Emits ordered list markers when a line begins with digits followed by a dot and whitespace.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param hasNewline true when a newline suffix exists for the line.
 * @param newlineStart inclusive start offset of the newline span.
 * @param newlineEnd exclusive end offset of the newline span.
 * @return true when the line was handled as an ordered list.
 */
internal fun handleOrderedListLine(
    tokens: MutableList<MarkdownToken>,
    lineText: String,
    lineStart: Int,
    hasNewline: Boolean,
    newlineStart: Int,
    newlineEnd: Int,
): Boolean {
    val firstNonWhitespace = firstNonWhitespaceIndex(lineText, 0)
    if (firstNonWhitespace == -1 || !lineText[firstNonWhitespace].isDigit()) {
        return false
    }

    var digitEnd = firstNonWhitespace
    while (digitEnd < lineText.length && lineText[digitEnd].isDigit()) {
        digitEnd += 1
    }
    if (digitEnd >= lineText.length || lineText[digitEnd] != '.') {
        return false
    }
    val markerEnd = digitEnd + 1
    if (markerEnd < lineText.length && !lineText[markerEnd].isWhitespace()) {
        return false
    }

    if (firstNonWhitespace > 0) {
        addToken(tokens, MarkdownTokenKind.PlainText, lineStart, lineStart + firstNonWhitespace)
    }
    addToken(tokens, MarkdownTokenKind.OrderedListMarker, lineStart + firstNonWhitespace, lineStart + markerEnd)
    if (markerEnd < lineText.length) {
        emitInlineTokens(
            tokens = tokens,
            baseKind = MarkdownTokenKind.PlainText,
            lineText = lineText,
            lineStart = lineStart,
            rangeStart = lineStart + markerEnd,
            rangeEndExclusive = lineStart + lineText.length,
        )
    }
    if (hasNewline) {
        addToken(tokens, MarkdownTokenKind.PlainText, newlineStart, newlineEnd)
    }
    return true
}

/**
 * Emits unordered list markers when a line begins with a list marker and whitespace.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param hasNewline true when a newline suffix exists for the line.
 * @param newlineStart inclusive start offset of the newline span.
 * @param newlineEnd exclusive end offset of the newline span.
 * @return true when the line was handled as an unordered list.
 */
internal fun handleUnorderedListLine(
    tokens: MutableList<MarkdownToken>,
    lineText: String,
    lineStart: Int,
    hasNewline: Boolean,
    newlineStart: Int,
    newlineEnd: Int,
): Boolean {
    val firstNonWhitespace = firstNonWhitespaceIndex(lineText, 0)
    if (firstNonWhitespace == -1) {
        return false
    }
    val marker = lineText[firstNonWhitespace]
    if (marker != '-' && marker != '*' && marker != '+') {
        return false
    }
    val markerEnd = firstNonWhitespace + 1
    if (markerEnd < lineText.length && !lineText[markerEnd].isWhitespace()) {
        return false
    }

    if (firstNonWhitespace > 0) {
        addToken(tokens, MarkdownTokenKind.PlainText, lineStart, lineStart + firstNonWhitespace)
    }
    addToken(tokens, MarkdownTokenKind.ListMarker, lineStart + firstNonWhitespace, lineStart + markerEnd)
    if (markerEnd < lineText.length) {
        emitInlineTokens(
            tokens = tokens,
            baseKind = MarkdownTokenKind.PlainText,
            lineText = lineText,
            lineStart = lineStart,
            rangeStart = lineStart + markerEnd,
            rangeEndExclusive = lineStart + lineText.length,
        )
    }
    if (hasNewline) {
        addToken(tokens, MarkdownTokenKind.PlainText, newlineStart, newlineEnd)
    }
    return true
}

/**
 * Emits table tokens when the line qualifies as part of a table region.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param hasNewline true when a newline suffix exists for the line.
 * @param newlineStart inclusive start offset of the newline span.
 * @param newlineEnd exclusive end offset of the newline span.
 * @param nextIsTableDelimiter true when the next line is a delimiter row.
 * @param inTableRegion true when a previous line established a table region.
 * @return table handling result for updating table-region state.
 */
internal fun handleTableLine(
    tokens: MutableList<MarkdownToken>,
    lineText: String,
    lineStart: Int,
    hasNewline: Boolean,
    newlineStart: Int,
    newlineEnd: Int,
    nextIsTableDelimiter: Boolean,
    inTableRegion: Boolean,
): TableHandlingResult {
    val isDelimiterRow = isTableDelimiterRow(lineText)
    val isTableCandidate = isDelimiterRow || nextIsTableDelimiter || inTableRegion
    if (!isTableCandidate || !lineText.contains('|')) {
        return TableHandlingResult(handled = false, inTableRegion = false)
    }

    val cellKind = when {
        isDelimiterRow -> MarkdownTokenKind.TableDelimiterRow
        nextIsTableDelimiter && !inTableRegion -> MarkdownTokenKind.TableHeaderText
        else -> MarkdownTokenKind.TableCellText
    }
    emitTableRowTokens(tokens, lineStart, lineText, cellKind)
    if (hasNewline) {
        addToken(tokens, MarkdownTokenKind.PlainText, newlineStart, newlineEnd)
    }

    val continueTable = !isDelimiterRow && lineText.contains('|')
    return TableHandlingResult(handled = true, inTableRegion = isDelimiterRow || inTableRegion || continueTable)
}

/**
 * Emits table tokens for a line that contains pipe separators.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param lineText line content without newline characters.
 * @param cellKind token kind to use for non-pipe segments.
 */
internal fun emitTableRowTokens(
    tokens: MutableList<MarkdownToken>,
    lineStart: Int,
    lineText: String,
    cellKind: MarkdownTokenKind,
) {
    var segmentStart = 0
    for (index in lineText.indices) {
        if (lineText[index] == '|') {
            emitTableCellSegment(tokens, cellKind, lineText, lineStart, segmentStart, index)
            addToken(tokens, MarkdownTokenKind.TablePipe, lineStart + index, lineStart + index + 1)
            segmentStart = index + 1
        }
    }
    emitTableCellSegment(tokens, cellKind, lineText, lineStart, segmentStart, lineText.length)
}

/**
 * Emits a table cell segment, applying inline parsing only for header/body cells.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param cellKind token kind for the cell content.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param localStart inclusive start offset within the line.
 * @param localEnd exclusive end offset within the line.
 */
internal fun emitTableCellSegment(
    tokens: MutableList<MarkdownToken>,
    cellKind: MarkdownTokenKind,
    lineText: String,
    lineStart: Int,
    localStart: Int,
    localEnd: Int,
) {
    val absoluteStart = lineStart + localStart
    val absoluteEnd = lineStart + localEnd
    if (cellKind == MarkdownTokenKind.TableDelimiterRow) {
        addToken(tokens, cellKind, absoluteStart, absoluteEnd)
        return
    }

    emitInlineTokens(
        tokens = tokens,
        baseKind = cellKind,
        lineText = lineText,
        lineStart = lineStart,
        rangeStart = absoluteStart,
        rangeEndExclusive = absoluteEnd,
    )
}

