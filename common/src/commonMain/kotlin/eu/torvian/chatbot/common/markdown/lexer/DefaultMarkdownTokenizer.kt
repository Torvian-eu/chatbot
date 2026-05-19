package eu.torvian.chatbot.common.markdown.lexer

import eu.torvian.chatbot.common.markdown.model.MarkdownToken
import eu.torvian.chatbot.common.markdown.model.MarkdownTokenKind

/**
 * Best-effort block-level tokenizer for markdown source text.
 *
 * The implementation is line-oriented and designed for editor-style highlighting
 * while remaining tolerant of incomplete input in streaming scenarios.
 */
object DefaultMarkdownTokenizer : MarkdownTokenizer {
    override fun tokenize(markdown: String): List<MarkdownToken> {
        if (markdown.isEmpty()) {
            return emptyList()
        }

        val lines = buildLines(markdown)
        val tokens = ArrayList<MarkdownToken>(lines.size * 3)
        var inCodeFence = false
        var inTableRegion = false

        for (index in lines.indices) {
            val line = lines[index]
            val lineText = markdown.substring(line.start, line.contentEnd)
            val lineStart = line.start
            val lineEnd = line.contentEnd
            val hasNewline = line.newlineEnd > line.contentEnd
            val newlineStart = line.contentEnd
            val newlineEnd = line.newlineEnd
            val nextLine = lines.getOrNull(index + 1)
            val nextIsTableDelimiter = nextLine?.let { isTableDelimiterRow(markdown, it) } ?: false

            if (inCodeFence) {
                inCodeFence = handleFenceContinuation(
                    tokens = tokens,
                    lineText = lineText,
                    lineStart = lineStart,
                    hasNewline = hasNewline,
                    newlineStart = newlineStart,
                    newlineEnd = newlineEnd,
                )
                continue
            }

            val fenceOpened = handleFenceOpening(
                tokens = tokens,
                lineText = lineText,
                lineStart = lineStart,
                hasNewline = hasNewline,
                newlineStart = newlineStart,
                newlineEnd = newlineEnd,
            )
            if (fenceOpened) {
                inCodeFence = true
                continue
            }

            val headingHandled = handleHeadingLine(
                tokens = tokens,
                lineText = lineText,
                lineStart = lineStart,
                hasNewline = hasNewline,
                newlineStart = newlineStart,
                newlineEnd = newlineEnd,
            )
            if (headingHandled) {
                inTableRegion = false
                continue
            }

            val blockquoteHandled = handleBlockquoteLine(
                tokens = tokens,
                lineText = lineText,
                lineStart = lineStart,
                hasNewline = hasNewline,
                newlineStart = newlineStart,
                newlineEnd = newlineEnd,
            )
            if (blockquoteHandled) {
                inTableRegion = false
                continue
            }

            val orderedListHandled = handleOrderedListLine(
                tokens = tokens,
                lineText = lineText,
                lineStart = lineStart,
                hasNewline = hasNewline,
                newlineStart = newlineStart,
                newlineEnd = newlineEnd,
            )
            if (orderedListHandled) {
                inTableRegion = false
                continue
            }

            val unorderedListHandled = handleUnorderedListLine(
                tokens = tokens,
                lineText = lineText,
                lineStart = lineStart,
                hasNewline = hasNewline,
                newlineStart = newlineStart,
                newlineEnd = newlineEnd,
            )
            if (unorderedListHandled) {
                inTableRegion = false
                continue
            }

            if (isHorizontalRuleLine(lineText)) {
                addToken(tokens, MarkdownTokenKind.HorizontalRule, lineStart, lineEnd)
                if (hasNewline) {
                    addToken(tokens, MarkdownTokenKind.PlainText, newlineStart, newlineEnd)
                }
                inTableRegion = false
                continue
            }

            val tableHandled = handleTableLine(
                tokens = tokens,
                lineText = lineText,
                lineStart = lineStart,
                hasNewline = hasNewline,
                newlineStart = newlineStart,
                newlineEnd = newlineEnd,
                nextIsTableDelimiter = nextIsTableDelimiter,
                inTableRegion = inTableRegion,
            )
            if (tableHandled.handled) {
                inTableRegion = tableHandled.inTableRegion
                continue
            }

            emitInlineTokens(
                tokens = tokens,
                baseKind = MarkdownTokenKind.PlainText,
                lineText = lineText,
                lineStart = lineStart,
                rangeStart = lineStart,
                rangeEndExclusive = lineEnd,
            )
            if (hasNewline) {
                addToken(tokens, MarkdownTokenKind.PlainText, newlineStart, newlineEnd)
            }
            inTableRegion = false
        }

        return tokens
    }
}
