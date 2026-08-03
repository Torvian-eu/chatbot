package eu.torvian.chatbot.worker.builtin.validation

/**
 * Default maximum number of characters allowed per rendered line in search output.
 *
 * Matching and context lines longer than this are windowed or trimmed so a single over-long
 * document line cannot bloat the tool output with raw text.
 */
internal const val SEARCH_MAX_LINE_CHARS: Int = 200

/**
 * Builds the [Regex] used to test each line of a searched document.
 *
 * In plain mode the [query] is treated literally (escaped) and, when [wholeWord] is set, wrapped in
 * word boundaries. In regex mode the [query] is used verbatim. Case-insensitivity is applied unless
 * [caseSensitive] is true.
 *
 * This helper is shared between `search_text` and the `fetch_web_content` search path so their
 * matching semantics stay identical and do not drift.
 *
 * @param query Raw query string.
 * @param mode Either `"plain"` or `"regex"`.
 * @param caseSensitive When false, matching ignores case.
 * @param wholeWord When true (plain mode only), matches are anchored to word boundaries.
 * @return Compiled [Regex] for line matching.
 * @throws IllegalArgumentException If the regex pattern is invalid.
 */
internal fun compileRegex(
    query: String,
    mode: String,
    caseSensitive: Boolean,
    wholeWord: Boolean,
): Regex {
    val pattern = if (mode == "regex") {
        query
    } else {
        if (wholeWord) "\\b${Regex.escape(query)}\\b" else Regex.escape(query)
    }
    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    return Regex(pattern, options)
}

/**
 * Windows a matching line around [matchRange] if it exceeds [maxChars], ensuring the match is visible.
 *
 * @param line The raw line string to be windowed.
 * @param matchRange The character index range where the match was found.
 * @param maxChars Maximum character limit for the windowed line.
 * @return The windowed line snippet with ellipsis markers where truncated.
 */
internal fun windowLineAroundMatch(
    line: String,
    matchRange: IntRange,
    maxChars: Int = SEARCH_MAX_LINE_CHARS,
): String {
    if (line.length <= maxChars) return line

    val matchLength = (matchRange.last - matchRange.first + 1).coerceAtLeast(1)
    val margin = ((maxChars - matchLength) / 2).coerceAtLeast(20)

    var winStart = maxOf(0, matchRange.first - margin)
    val winEnd = minOf(line.length, winStart + maxChars)
    if (winEnd == line.length) {
        winStart = maxOf(0, winEnd - maxChars)
    }

    val snippet = line.substring(winStart, winEnd)
    val prefix = if (winStart > 0) "..." else ""
    val suffix = if (winEnd < line.length) "..." else ""
    return "$prefix$snippet$suffix"
}

/**
 * Trims a context line if it exceeds [maxChars], appending an ellipsis suffix.
 *
 * @param ctx The context line string.
 * @param maxChars Maximum character limit for a context line.
 * @return The trimmed context line if too long, or the original line otherwise.
 */
internal fun trimContext(ctx: String, maxChars: Int = SEARCH_MAX_LINE_CHARS): String =
    if (ctx.length > maxChars) ctx.take(maxChars) + "..." else ctx

/**
 * Trims a context line from the start if it exceeds [maxChars], prefixing an ellipsis.
 *
 * Unlike [trimContext] (which keeps the head of the line and trims the tail), this keeps the **tail**
 * of the line — the part nearest a following match. It is used for `contextBefore` lines so the
 * context reads as a contiguous block of characters flowing into the match line.
 *
 * @param ctx The context line string.
 * @param maxChars Maximum character limit for a context line.
 * @return The trimmed context line if too long, or the original line otherwise.
 */
internal fun trimContextStart(ctx: String, maxChars: Int = SEARCH_MAX_LINE_CHARS): String =
    if (ctx.length > maxChars) "..." + ctx.takeLast(maxChars) else ctx

/**
 * Renderable representation of a single matching line within one searched document.
 *
 * The document label is intentionally omitted here: it is emitted once as a header during rendering
 * (see the grouped-output block in [eu.torvian.chatbot.worker.builtin.impl.SearchTextTool]) rather
 * than repeated on every line, which keeps the textual output compact and token-friendly for the
 * consuming model.
 *
 * @property lineNumber 1-based line number of the match within its document.
 * @property line Content of the matching line.
 * @property before Context lines preceding the match, each paired with its 1-based line number.
 * @property after Context lines following the match, each paired with its 1-based line number.
 */
internal data class MatchRender(
    val lineNumber: Int,
    val line: String,
    val before: List<Pair<Int, String>>,
    val after: List<Pair<Int, String>>,
)

/**
 * Represents one unique source line in the compact readable rendering of a document.
 *
 * @property lineNumber 1-based source line number used as the deduplication key.
 * @property text Rendered source content, either context-trimmed or match-centered.
 */
internal data class RenderedLine(
    val lineNumber: Int,
    val text: String,
)

/**
 * Compacts per-match renderings into a unique, source-ordered set of lines for readable output.
 *
 * Context entries are inserted only when their source line has not already been seen. Matching
 * entries always replace an existing context entry, ensuring a line that is itself a match keeps
 * its match-centered rendering rather than a potentially shortened context rendering.
 *
 * @param renders Per-match renderings belonging to one document, in match discovery order.
 * @return Unique rendered lines sorted by their 1-based source line number.
 */
internal fun compactRenders(renders: List<MatchRender>): List<RenderedLine> {
    val linesByNumber = sortedMapOf<Int, RenderedLine>()

    renders.forEach { render ->
        render.before.forEach { (lineNumber, text) ->
            linesByNumber.putIfAbsent(lineNumber, RenderedLine(lineNumber, text))
        }
        linesByNumber[render.lineNumber] = RenderedLine(render.lineNumber, render.line)
        render.after.forEach { (lineNumber, text) ->
            linesByNumber.putIfAbsent(lineNumber, RenderedLine(lineNumber, text))
        }
    }

    return linesByNumber.values.toList()
}
