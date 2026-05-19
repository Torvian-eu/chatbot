package eu.torvian.chatbot.common.markdown.lexer

import eu.torvian.chatbot.common.markdown.model.MarkdownToken
import eu.torvian.chatbot.common.markdown.model.MarkdownTokenKind

/**
 * Emits inline tokens within a line segment, falling back to the base kind for plain spans.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param baseKind token kind used for non-inline spans.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param rangeStart inclusive start offset of the segment to tokenize.
 * @param rangeEndExclusive exclusive end offset of the segment to tokenize.
 */
internal fun emitInlineTokens(
    tokens: MutableList<MarkdownToken>,
    baseKind: MarkdownTokenKind,
    lineText: String,
    lineStart: Int,
    rangeStart: Int,
    rangeEndExclusive: Int,
) {
    if (rangeStart >= rangeEndExclusive) {
        return
    }
    if (!isInlineEligible(baseKind)) {
        addToken(tokens, baseKind, rangeStart, rangeEndExclusive)
        return
    }

    val localStart = rangeStart - lineStart
    val localEnd = rangeEndExclusive - lineStart
    if (localStart < 0 || localEnd > lineText.length) {
        addToken(tokens, baseKind, rangeStart, rangeEndExclusive)
        return
    }

    var index = localStart
    var plainStart = localStart

    while (index < localEnd) {
        val char = lineText[index]
        if (char == '\\' && index + 1 < localEnd) {
            emitPlainSegment(tokens, baseKind, lineStart, plainStart, index)
            addToken(tokens, MarkdownTokenKind.EscapeSequence, lineStart + index, lineStart + index + 2)
            index += 2
            plainStart = index
            continue
        }
        if (char == '`') {
            val closing = findClosingChar(lineText, index + 1, localEnd, '`')
            if (closing == -1) {
                break
            }
            emitPlainSegment(tokens, baseKind, lineStart, plainStart, index)
            addToken(tokens, MarkdownTokenKind.InlineCodeDelimiter, lineStart + index, lineStart + index + 1)
            if (closing > index + 1) {
                addToken(tokens, MarkdownTokenKind.InlineCodeText, lineStart + index + 1, lineStart + closing)
            }
            addToken(tokens, MarkdownTokenKind.InlineCodeDelimiter, lineStart + closing, lineStart + closing + 1)
            index = closing + 1
            plainStart = index
            continue
        }
        if (char == '!' && index + 1 < localEnd && lineText[index + 1] == '[') {
            emitPlainSegment(tokens, baseKind, lineStart, plainStart, index)
            addToken(tokens, MarkdownTokenKind.ImageMarker, lineStart + index, lineStart + index + 1)
            index = emitLinkTokens(tokens, lineText, lineStart, index + 1, localEnd)
            plainStart = index
            continue
        }
        if (char == '[') {
            emitPlainSegment(tokens, baseKind, lineStart, plainStart, index)
            index = emitLinkTokens(tokens, lineText, lineStart, index, localEnd)
            plainStart = index
            continue
        }
        if (char == '~' && index + 1 < localEnd && lineText[index + 1] == '~') {
            val closing = findClosingSequence(lineText, index + 2, localEnd, "~~")
            if (closing == -1) {
                index += 2
                continue
            }
            emitPlainSegment(tokens, baseKind, lineStart, plainStart, index)
            addToken(tokens, MarkdownTokenKind.StrikeDelimiter, lineStart + index, lineStart + index + 2)
            if (closing > index + 2) {
                addToken(tokens, MarkdownTokenKind.StrikeText, lineStart + index + 2, lineStart + closing)
            }
            addToken(tokens, MarkdownTokenKind.StrikeDelimiter, lineStart + closing, lineStart + closing + 2)
            index = closing + 2
            plainStart = index
            continue
        }
        if (char == '*' && index + 1 < localEnd && lineText[index + 1] == '*') {
            val closing = findClosingSequence(lineText, index + 2, localEnd, "**")
            if (closing == -1) {
                index += 2
                continue
            }
            emitPlainSegment(tokens, baseKind, lineStart, plainStart, index)
            addToken(tokens, MarkdownTokenKind.StrongDelimiter, lineStart + index, lineStart + index + 2)
            if (closing > index + 2) {
                addToken(tokens, MarkdownTokenKind.StrongText, lineStart + index + 2, lineStart + closing)
            }
            addToken(tokens, MarkdownTokenKind.StrongDelimiter, lineStart + closing, lineStart + closing + 2)
            index = closing + 2
            plainStart = index
            continue
        }
        if (char == '_' && index + 1 < localEnd && lineText[index + 1] == '_') {
            val closing = findClosingSequence(lineText, index + 2, localEnd, "__")
            if (closing == -1) {
                index += 2
                continue
            }
            emitPlainSegment(tokens, baseKind, lineStart, plainStart, index)
            addToken(tokens, MarkdownTokenKind.StrongDelimiter, lineStart + index, lineStart + index + 2)
            if (closing > index + 2) {
                addToken(tokens, MarkdownTokenKind.StrongText, lineStart + index + 2, lineStart + closing)
            }
            addToken(tokens, MarkdownTokenKind.StrongDelimiter, lineStart + closing, lineStart + closing + 2)
            index = closing + 2
            plainStart = index
            continue
        }
        if (char == '*' || char == '_') {
            val closing = findClosingChar(lineText, index + 1, localEnd, char)
            if (closing != -1) {
                emitPlainSegment(tokens, baseKind, lineStart, plainStart, index)
                addToken(tokens, MarkdownTokenKind.EmphasisDelimiter, lineStart + index, lineStart + index + 1)
                if (closing > index + 1) {
                    addToken(tokens, MarkdownTokenKind.EmphasisText, lineStart + index + 1, lineStart + closing)
                }
                addToken(tokens, MarkdownTokenKind.EmphasisDelimiter, lineStart + closing, lineStart + closing + 1)
                index = closing + 1
                plainStart = index
                continue
            }
        }
        index += 1
    }

    emitPlainSegment(tokens, baseKind, lineStart, plainStart, localEnd)
}

/**
 * Emits a plain segment for inline parsing using the base kind.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param baseKind token kind used for plain spans.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param localStart inclusive start offset within the line.
 * @param localEnd exclusive end offset within the line.
 */
internal fun emitPlainSegment(
    tokens: MutableList<MarkdownToken>,
    baseKind: MarkdownTokenKind,
    lineStart: Int,
    localStart: Int,
    localEnd: Int,
) {
    if (localStart >= localEnd) {
        return
    }
    addToken(tokens, baseKind, lineStart + localStart, lineStart + localEnd)
}

/**
 * Emits link tokens for a label starting at the given bracket index.
 *
 * @param tokens target list that accumulates tokens in source order.
 * @param lineText line content without newline characters.
 * @param lineStart inclusive start offset of the line in the source string.
 * @param bracketIndex index of the opening '[' within the line.
 * @param localEnd exclusive end offset within the line.
 * @return the next local index to continue scanning from.
 */
internal fun emitLinkTokens(
    tokens: MutableList<MarkdownToken>,
    lineText: String,
    lineStart: Int,
    bracketIndex: Int,
    localEnd: Int,
): Int {
    addToken(tokens, MarkdownTokenKind.LinkTextDelimiter, lineStart + bracketIndex, lineStart + bracketIndex + 1)
    val textEnd = findClosingChar(lineText, bracketIndex + 1, localEnd, ']')
    if (textEnd == -1) {
        addToken(tokens, MarkdownTokenKind.LinkText, lineStart + bracketIndex + 1, lineStart + localEnd)
        return localEnd
    }

    if (textEnd > bracketIndex + 1) {
        addToken(tokens, MarkdownTokenKind.LinkText, lineStart + bracketIndex + 1, lineStart + textEnd)
    }
    addToken(tokens, MarkdownTokenKind.LinkTextDelimiter, lineStart + textEnd, lineStart + textEnd + 1)

    var index = textEnd + 1
    if (index < localEnd && lineText[index] == '(') {
        addToken(tokens, MarkdownTokenKind.LinkUrlDelimiter, lineStart + index, lineStart + index + 1)
        val urlEnd = findClosingChar(lineText, index + 1, localEnd, ')')
        if (urlEnd == -1) {
            addToken(tokens, MarkdownTokenKind.LinkUrl, lineStart + index + 1, lineStart + localEnd)
            return localEnd
        }
        if (urlEnd > index + 1) {
            addToken(tokens, MarkdownTokenKind.LinkUrl, lineStart + index + 1, lineStart + urlEnd)
        }
        addToken(tokens, MarkdownTokenKind.LinkUrlDelimiter, lineStart + urlEnd, lineStart + urlEnd + 1)
        index = urlEnd + 1
    }

    return index
}

/**
 * Returns true when the base kind should be further tokenized with inline rules.
 *
 * @param baseKind token kind for the surrounding text span.
 * @return true when inline parsing should apply to the span.
 */
internal fun isInlineEligible(baseKind: MarkdownTokenKind): Boolean = when (baseKind) {
    MarkdownTokenKind.PlainText,
    MarkdownTokenKind.HeadingText,
    MarkdownTokenKind.BlockquoteText,
    MarkdownTokenKind.TableHeaderText,
    MarkdownTokenKind.TableCellText -> true
    else -> false
}

/**
 * Finds the next unescaped instance of the requested closing character.
 *
 * @param text line content without newline characters.
 * @param start local index to start searching from.
 * @param end local exclusive end index for the search.
 * @param target closing character to locate.
 * @return local index of the closing character, or -1 if none is found.
 */
internal fun findClosingChar(text: String, start: Int, end: Int, target: Char): Int {
    var index = start
    while (index < end) {
        if (text[index] == target && !isEscaped(text, index)) {
            return index
        }
        index += 1
    }
    return -1
}

/**
 * Finds the next unescaped instance of the requested closing sequence.
 *
 * @param text line content without newline characters.
 * @param start local index to start searching from.
 * @param end local exclusive end index for the search.
 * @param sequence closing sequence to locate.
 * @return local index of the closing sequence, or -1 if none is found.
 */
internal fun findClosingSequence(text: String, start: Int, end: Int, sequence: String): Int {
    var index = start
    while (index <= end - sequence.length) {
        if (text.regionMatches(index, sequence, 0, sequence.length) && !isEscaped(text, index)) {
            return index
        }
        index += 1
    }
    return -1
}

/**
 * Checks whether the character at the provided index is escaped by a backslash.
 *
 * @param text line content without newline characters.
 * @param index local index to inspect.
 * @return true when the character is immediately preceded by a backslash.
 */
internal fun isEscaped(text: String, index: Int): Boolean {
    if (index <= 0) {
        return false
    }
    return text[index - 1] == '\\'
}

