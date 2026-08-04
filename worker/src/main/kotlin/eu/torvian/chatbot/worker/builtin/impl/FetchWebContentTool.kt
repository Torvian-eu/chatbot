package eu.torvian.chatbot.worker.builtin.impl

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.net.WebFetchRequest
import eu.torvian.chatbot.worker.builtin.net.WebFetchResult
import eu.torvian.chatbot.worker.builtin.net.WebFetchService
import eu.torvian.chatbot.worker.builtin.net.mapWebFetchErrorToToolResult
import eu.torvian.chatbot.worker.builtin.validation.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Fetches textual content from a public internet URL.
 *
 * The tool is a thin orchestration layer over the shared worker web foundation: it parses the tool
 * input, delegates all URL validation and HTTP transport to [WebFetchService], and only adds the
 * textual concerns (content-type gating, charset-aware decoding, and result shaping). It never
 * performs its own DNS, socket, or redirect logic, so the security policy lives in exactly one place.
 *
 * Binary/non-text responses are rejected rather than emitted as garbage, and decoding failures are
 * surfaced as explicit errors. `returnMode` (`auto`/`text`/`html`) is accepted for forward
 * compatibility but, in this v1, all modes return the decoded body text verbatim (no HTML cleaning).
 *
 * @property fetchService Shared, transport-agnostic web-fetch service (validates URLs, issues GETs,
 *   enforces timeouts and size caps, and follows redirects only when requested).
 */
class FetchWebContentTool(
    private val fetchService: WebFetchService,
) : BuiltInTool {

    override val name: String = "fetch_web_content"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf(
            "url", "timeoutSeconds", "maxBytes", "maxLines", "followRedirects", "returnMode",
            "range", "searchQuery", "searchMode", "contextBefore", "contextAfter", "maxResults",
            "caseSensitive", "wholeWord"
        )
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val url = parseRequiredString(input, "url", validationErrors)
        if (url != null && url.isBlank()) {
            validationErrors.add("Argument 'url' must not be blank")
        }

        val timeoutSeconds = parseOptionalInt(input, "timeoutSeconds", 5, validationErrors)
        if (timeoutSeconds <= 0) {
            validationErrors.add("Argument 'timeoutSeconds' must be > 0")
        }

        val maxBytes = parseOptionalInt(input, "maxBytes", defaultValue = 1200, validationErrors)
        if (maxBytes <= 0) {
            validationErrors.add("Argument 'maxBytes' must be > 0")
        } else if (maxBytes > MAX_PRESENTATION_BYTES) {
            validationErrors.add("Argument 'maxBytes' must be <= $MAX_PRESENTATION_BYTES")
        }

        val maxLines = parseOptionalInt(input, "maxLines", defaultValue = 25, validationErrors)
        if (maxLines <= 0) {
            validationErrors.add("Argument 'maxLines' must be > 0")
        }

        val followRedirects = parseOptionalBoolean(input, "followRedirects", defaultValue = true, validationErrors)

        val returnMode = parseOptionalString(input, "returnMode", validationErrors) ?: "auto"
        if (returnMode !in setOf("auto", "text", "html")) {
            validationErrors.add("Invalid 'returnMode' value: $returnMode (expected 'auto', 'text', or 'html')")
        }

        val range = parseOptionalLineRange(input, "range", validationErrors)

        // --- Search parameters (mutually exclusive with 'range') -----------------------------------
        val searchQuery = parseOptionalString(input, "searchQuery", validationErrors)
        if (searchQuery != null && searchQuery.isBlank()) {
            validationErrors.add("Argument 'searchQuery' must not be blank")
        }

        val searchMode = parseOptionalString(input, "searchMode", validationErrors) ?: "regex"
        if (searchMode !in setOf("plain", "regex")) {
            validationErrors.add("Invalid 'searchMode' value: $searchMode (expected 'plain' or 'regex')")
        }

        val caseSensitive = parseOptionalBoolean(input, "caseSensitive", defaultValue = false, validationErrors)
        val wholeWord = parseOptionalBoolean(input, "wholeWord", defaultValue = false, validationErrors)
        if (searchMode == "regex" && wholeWord) {
            validationErrors.add("Argument 'wholeWord' is only supported in 'plain' mode")
        }

        val contextBefore = parseOptionalInt(input, "contextBefore", defaultValue = 1, validationErrors)
        if (contextBefore < 0) {
            validationErrors.add("Argument 'contextBefore' must be >= 0")
        }

        val contextAfter = parseOptionalInt(input, "contextAfter", defaultValue = 2, validationErrors)
        if (contextAfter < 0) {
            validationErrors.add("Argument 'contextAfter' must be >= 0")
        }

        val maxResults = parseOptionalInt(input, "maxResults", defaultValue = 10, validationErrors)
        if (maxResults < 1) {
            validationErrors.add("Argument 'maxResults' must be >= 1")
        }

        // Mutual exclusivity: 'range' and 'searchQuery' cannot be combined.
        val rangeSet = input.containsKey("range")
        val searchSet = input.containsKey("searchQuery")
        if (rangeSet && searchSet) {
            validationErrors.add("Only one of 'range' and 'searchQuery' may be used at a time")
        }

        // The searchQuery-family parameters are only meaningful when searchQuery is present.
        val searchOnlyParams = listOf(
            "searchMode", "contextBefore", "contextAfter", "maxResults", "caseSensitive", "wholeWord"
        )
        if (!searchSet) {
            searchOnlyParams.forEach { key ->
                if (input.containsKey(key)) {
                    validationErrors.add("Argument '$key' can only be used when 'searchQuery' is specified")
                }
            }
        }

        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        // --- Delegate to the shared web foundation (no URL/HTTP logic here) -----------------------
        val request = WebFetchRequest(
            url = url!!,
            timeoutSeconds = timeoutSeconds,
            maxBytes = MAX_DOWNLOAD_BYTES,
            followRedirects = followRedirects,
        )

        val fetchedResult = when (val fetched = fetchService.fetch(request)) {
            is Either.Left -> return mapWebFetchErrorToToolResult(fetched.value)
            is Either.Right -> fetched.value
        }

        // --- Textual gating: never emit binary garbage -------------------------------------------
        val parsed = parseContentType(fetchedResult.contentType)
        val (mediaType, charsetName) = parsed
        if (!isTextualContentType(mediaType)) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.EXECUTION_FAILED,
                "Response content type '${fetchedResult.contentType ?: "<none>"}' is not textual; refusing to emit binary content."
            )
        }

        val charset = resolveCharset(charsetName)
        val text = decodeText(fetchedResult.bodyBytes, charset)
            ?: return builtInToolErrorResult(
                BuiltInToolExecutionError.EXECUTION_FAILED,
                "Response body could not be decoded as text using charset '${charset.name()}'."
            )

        // --- Shape the result (output + structured details) --------------------------------------
        val allLines = text.lines()

        if (searchQuery != null) {
            return renderSearchResult(
                finalUrl = fetchedResult.finalUrl,
                allLines = allLines,
                searchQuery = searchQuery,
                searchMode = searchMode,
                caseSensitive = caseSensitive,
                wholeWord = wholeWord,
                contextBefore = contextBefore,
                contextAfter = contextAfter,
                maxResults = maxResults,
                maxLines = maxLines,
                maxBytes = maxBytes,
                returnMode = returnMode,
                fetchedResult = fetchedResult,
            )
        }

        val (startIdx, endIdx) = resolveSlice(range, allLines.size)
        val selected = allLines.subList(startIdx, endIdx)
        val rawBody = selected.joinToString("\n")
        val truncationResult = truncateLinesAndBytes(rawBody, maxLines, maxBytes)
        val body = truncationResult.text
        val linesShown = truncationResult.linesShown
        val bytesShown = truncationResult.bytesShown
        val truncated = truncationResult.isTruncated

        val notice = if (truncated) {
            formatTruncationNotice(
                linesShown,
                bytesShown,
                "Use 'range' or increase 'maxLines'/'maxBytes' to read further."
            )
        } else {
            ""
        }
        val actualEndIdx = startIdx + linesShown
        val header = buildRangeHeader(fetchedResult.finalUrl, startIdx, actualEndIdx, allLines.size)
        val content = if (body.isEmpty()) header else "$header\n$body"
        val output = content + notice

        val details = buildJsonObject {
            put("finalUrl", fetchedResult.finalUrl)
            put("statusCode", fetchedResult.statusCode)
            put("contentType", fetchedResult.contentType)
            put("contentLength", fetchedResult.contentLength)
            put("bytesRead", fetchedResult.bodyBytes.size)
            put("returnMode", returnMode)
            put("totalLines", allLines.size)
            put("truncated", truncated)
        }

        return BuiltInToolExecutionResult(
            output = output,
            details = details,
        )
    }

    /**
     * Renders a search-based result for a fetched page body.
     *
     * When the LLM supplies a [searchQuery], the fetched page is searched in-memory (line-based,
     * matching the shared text-search semantics) instead of returning a contiguous line slice. The
     * matching lines, plus their context windows, are compacted into a source-ordered readable output
     * under a search header, then subject to the standard [maxLines]/[maxBytes] truncation so that
     * `maxLines` counts the total lines returned (including context).
     *
     * @param finalUrl The final URL after redirects, used in the output header.
     * @param allLines The decoded page lines (no line terminators).
     * @param searchQuery The validated, non-blank search query.
     * @param searchMode Either `"plain"` or `"regex"`.
     * @param caseSensitive Whether matching is case-sensitive.
     * @param wholeWord Whether to anchor matches to word boundaries (plain mode only).
     * @param contextBefore Context before each match: a budget of `contextBefore * 80` characters
     *   spent first on revealing the matching line, then on preceding lines. Each preceding line
     *   consumes from the remaining budget (short lines leave room for more), so the number of lines
     *   shown is not fixed.
     * @param contextAfter Context after each match: a budget of `contextAfter * 80` characters
     *   spent first on revealing the matching line, then on following lines. Each following line
     *   consumes from the remaining budget (short lines leave room for more), so the number of lines
     *   shown is not fixed.
     * @param maxResults Maximum number of matching lines to return.
     * @param maxLines Maximum total output lines (including context) to return.
     * @param maxBytes Maximum output bytes to return.
     * @param returnMode The accepted `returnMode` value (echoed in details).
     * @param fetchedResult The raw fetch result, for URL/status/type metadata.
     * @return A search-shaped [BuiltInToolExecutionResult].
     */
    private fun renderSearchResult(
        finalUrl: String,
        allLines: List<String>,
        searchQuery: String,
        searchMode: String,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        contextBefore: Int,
        contextAfter: Int,
        maxResults: Int,
        maxLines: Int,
        maxBytes: Int,
        returnMode: String,
        fetchedResult: WebFetchResult,
    ): BuiltInToolExecutionResult {
        // Compile the matcher once. A bad regex is an explicit error rather than silently finding nothing.
        val regex = try {
            compileRegex(searchQuery, searchMode, caseSensitive, wholeWord)
        } catch (e: Exception) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.INVALID_INPUT,
                "Invalid ${if (searchMode == "regex") "regular expression" else "pattern"}: ${e.message}"
            )
        }

        // Occurrence-based matching: web pages are often minified onto very few (or a single) lines,
        // so counting matching *lines* would undercount results. Every occurrence is counted. Each
        // occurrence is rendered as a windowed snippet centered on its match (showing surrounding
        // context) and saved as an [OutputLine]. A final compaction pass merges overlapping windows
        // so the output never repeats text and drops duplicate context lines.
        var totalMatches = 0
        var truncatedByMaxResults = false
        val outputLines = mutableListOf<OutputLine>()

        outer@ for ((index, line) in allLines.withIndex()) {
            val matchRanges = regex.findAll(line).map { it.range }.toList()
            if (matchRanges.isEmpty()) continue

            // Number of occurrences from this line we may still emit before hitting maxResults.
            val quota = (maxResults - totalMatches).coerceAtLeast(0)
            if (quota == 0) {
                truncatedByMaxResults = true
                break@outer
            }
            val toRender = matchRanges.take(quota)
            if (toRender.size < matchRanges.size) {
                truncatedByMaxResults = true
            }

            val beforeBudget = contextBefore * CONTEXT_CHARS_PER_LINE
            val afterBudget = contextAfter * CONTEXT_CHARS_PER_LINE

            // Each match occurrence gets its own independent context budget on both sides. For a given
            // occurrence the budget is spent first on revealing the long (often minified) match line
            // past the fixed window boundary, and the leftover funds the surrounding context lines.
            for (matchRange in toRender) {
                val span = windowSpan(line, matchRange, beforeBudget, afterBudget)

                // Context-before lines consume from this occurrence's leftover before-budget, walking
                // outward from the match. They keep their tail (nearest the match) so the block reads
                // contiguously into the match line, hence trimming from the start.
                val beforeList = mutableListOf<OutputLine>()
                var beforeLeft = span.beforeLeft
                var bIdx = index - 1
                while (bIdx >= maxOf(0, index - contextBefore) && beforeLeft > 0) {
                    val lineText = allLines[bIdx]
                    // Each line consumes at least one full context unit from the budget, even when it
                    // is short, so a run of near-empty lines cannot eat the whole budget and flood the
                    // output with empty lines.
                    val consumed = minOf(beforeLeft, maxOf(lineText.length, CONTEXT_CHARS_PER_LINE))
                    beforeList.add(
                        OutputLine(
                            lineNumber = bIdx + 1,
                            content = trimContextStart(lineText, consumed),
                            isContext = true
                        )
                    )
                    beforeLeft -= consumed
                    bIdx--
                }
                outputLines.addAll(beforeList.asReversed()) // ascending line order for display

                outputLines.add(
                    OutputLine(
                        lineNumber = index + 1,
                        content = renderWindow(line, span.start, span.end),
                        isContext = false,
                        sourceStart = span.start,
                        sourceEnd = span.end,
                    )
                )

                // Context-after lines consume from this occurrence's leftover after-budget, walking
                // outward from the match. They keep their head (nearest the match) so the block reads
                // contiguously from the match line, hence trimming from the end.
                val afterList = mutableListOf<OutputLine>()
                var afterLeft = span.afterLeft
                var aIdx = index + 1
                while (aIdx < minOf(allLines.size, index + 1 + contextAfter) && afterLeft > 0) {
                    val lineText = allLines[aIdx]
                    // Each line consumes at least one full context unit from the budget, even when it
                    // is short, so a run of near-empty lines cannot eat the whole budget and flood the
                    // output with empty lines.
                    val consumed = minOf(afterLeft, maxOf(lineText.length, CONTEXT_CHARS_PER_LINE))
                    afterList.add(
                        OutputLine(
                            lineNumber = aIdx + 1,
                            content = trimContext(lineText, consumed),
                            isContext = true
                        )
                    )
                    afterLeft -= consumed
                    aIdx++
                }
                outputLines.addAll(afterList)
            }
            totalMatches += toRender.size
        }

        val compacted = compactSearchOutput(outputLines, allLines)
        val rawOutput = compacted.joinToString("\n") { "${it.lineNumber}: ${it.content}" }
        val truncationResult = truncateLinesAndBytes(rawOutput, maxLines, maxBytes)
        val body = truncationResult.text
        val linesShown = truncationResult.linesShown
        val bytesShown = truncationResult.bytesShown
        val truncated = truncationResult.isTruncated || truncatedByMaxResults

        val header = "=== $finalUrl (search: \"$searchQuery\", $totalMatches matches) ==="
        val notice = if (truncated) {
            formatTruncationNotice(
                linesShown,
                bytesShown,
                "Increase 'maxResults'/'maxLines'/'maxBytes' or use 'contextBefore'/'contextAfter' to read further."
            )
        } else {
            ""
        }
        val content = if (body.isEmpty()) header else "$header\n$body"
        val output = content + notice

        val details = buildJsonObject {
            put("finalUrl", fetchedResult.finalUrl)
            put("statusCode", fetchedResult.statusCode)
            put("contentType", fetchedResult.contentType)
            put("contentLength", fetchedResult.contentLength)
            put("bytesRead", fetchedResult.bodyBytes.size)
            put("returnMode", returnMode)
            put("searchQuery", searchQuery)
            put("searchMode", searchMode)
            put("totalMatches", totalMatches)
            put("totalLines", allLines.size)
            put("truncated", truncated)
        }

        return BuiltInToolExecutionResult(
            output = output,
            details = details,
        )
    }

    /**
     * Computes the absolute `[start, end)` character span of the snippet that should be rendered
     * for [matchRange] within [line], together with how much of each side's context budget the window
     * consumed.
     *
     * The window is centered on the match: the default [SEARCH_MAX_LINE_CHARS] window leaves a
     * balanced margin on each side so the match is visible with surrounding context. The caller
     * provides a per-side character budget ([beforeBudget]/[afterBudget], derived from the requested
     * context count) that is spent *first* on revealing the long match line past the fixed boundary
     * before it is used for context lines, capped at the line edges. This lets the LLM read more of a
     * long (often minified) line instead of it ending in a `...`.
     *
     * @param line The full source line string.
     * @param matchRange Character range of the occurrence.
     * @param beforeBudget Character budget available for the left side of the window.
     * @param afterBudget Character budget available for the right side of the window.
     * @return A [MatchWindow] with `start`/`end` offsets into [line] and the budget left over on
     *   each side after the window consumed its share.
     */
    private fun windowSpan(line: String, matchRange: IntRange, beforeBudget: Int, afterBudget: Int): MatchWindow {
        val matchLength = (matchRange.last - matchRange.first + 1).coerceAtLeast(1)
        // Base window centered on the match, mirroring the shared [windowLineAroundMatch] math exactly
        // so the default (no context) behavior is unchanged: a SEARCH_MAX_LINE_CHARS-wide window
        // positioned by margin, slid back when it overruns the line end.
        val margin = ((SEARCH_MAX_LINE_CHARS - matchLength) / 2).coerceAtLeast(20)
        var start = maxOf(0, matchRange.first - margin)
        var end = minOf(line.length, start + SEARCH_MAX_LINE_CHARS)
        if (end == line.length) start = maxOf(0, end - SEARCH_MAX_LINE_CHARS)

        // Each side spends its context budget on extending the window, clamped to the line edges. The
        // leftover is returned so the caller can fund context lines from it.
        val consumedBefore = minOf(beforeBudget, start)
        val consumedAfter = minOf(afterBudget, line.length - end)
        start -= consumedBefore
        end += consumedAfter
        return MatchWindow(
            start = start,
            end = end,
            beforeLeft = beforeBudget - consumedBefore,
            afterLeft = afterBudget - consumedAfter
        )
    }

    /**
     * Renders a source-line window as a snippet with leading/trailing ellipsis where it is cut off.
     *
     * @param line The full source line string.
     * @param start Character offset where the window begins.
     * @param end Character offset where the window ends (exclusive).
     * @return The source substring between [start] and [end], prefixed with `...` when it does not
     *   begin at the line start and suffixed with `...` when it does not reach the line end.
     */
    private fun renderWindow(line: String, start: Int, end: Int): String {
        val snippet = line.substring(start, end)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < line.length) "..." else ""
        return "$prefix$snippet$suffix"
    }

    /**
     * Compacts the collected [OutputLine]s into a final, source-ordered, non-overlapping rendering.
     *
     * Matching windows (non-context lines) that overlap in source span are merged into a single
     * window so no source text is repeated. Each merged window is rendered as the source substring it
     * covers, with a leading ellipsis when it does not begin at the line start and a trailing ellipsis
     * when it does not reach the line end — so the first result naturally carries a trailing ellipsis
     * when more content follows, and later results show a leading ellipsis indicating the context
     * before the match. Duplicate context lines (whole source lines) are emitted once.
     *
     * @param lines The raw [OutputLine]s in discovery order.
     * @param allLines The full page lines, used to render merged windows from their spans.
     * @return The compacted [OutputLine]s, ready for textual rendering.
     */
    private fun compactSearchOutput(lines: List<OutputLine>, allLines: List<String>): List<OutputLine> {
        // Group by source line number while preserving first-seen order.
        val grouped = LinkedHashMap<Int, MutableList<OutputLine>>()
        for (line in lines) grouped.getOrPut(line.lineNumber) { mutableListOf() }.add(line)

        val result = mutableListOf<OutputLine>()
        for ((lineNumber, group) in grouped) {
            val matchLines = group.filter { !it.isContext }
            val contextLines = group.filter { it.isContext }

            if (matchLines.isEmpty()) {
                // Only context lines (whole source lines): emit the first, they are identical.
                result.add(contextLines.first())
                continue
            }

            // Merge overlapping match windows by span, in ascending source offset.
            val sortedMatches = matchLines.sortedBy { it.sourceStart }
            val merged = mutableListOf<OutputLine>()
            for (m in sortedMatches) {
                if (merged.isEmpty() || m.sourceStart > merged.last().sourceEnd) {
                    merged.add(m)
                } else {
                    val last = merged.last()
                    merged[merged.lastIndex] = last.copy(sourceEnd = maxOf(last.sourceEnd, m.sourceEnd))
                }
            }

            val lineText = allLines[lineNumber - 1]
            for (w in merged) {
                val snippet = lineText.substring(w.sourceStart, w.sourceEnd)
                val prefix = if (w.sourceStart > 0) "..." else ""
                val suffix = if (w.sourceEnd < lineText.length) "..." else ""
                result.add(w.copy(content = "$prefix$snippet$suffix"))
            }
            // Context lines are grouped by their own source line number, so none remain here.
        }
        return result
    }

    /**
     * Splits a raw `Content-Type` header into its lower-cased media type and charset parameter.
     *
     * Only the first segment is treated as the media type; subsequent `name=value` segments are
     * scanned for a `charset` (case-insensitive, tolerant of surrounding quotes). A blank or missing
     * header yields `(null, null)`.
     *
     * @param raw The raw `Content-Type` header value, or null when absent.
     * @return A pair of `(mediaType, charset)`, each possibly null.
     */
    private fun parseContentType(raw: String?): Pair<String?, String?> {
        if (raw.isNullOrBlank()) return null to null
        val segments = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val mediaType = segments.firstOrNull()?.lowercase()
        var charset: String? = null
        for (segment in segments.drop(1)) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            val name = segment.substring(0, eq).trim().lowercase()
            if (name == "charset") {
                charset = segment.substring(eq + 1).trim().trim('"')
            }
        }
        return mediaType to charset
    }

    /**
     * Decides whether [mediaType] is a clearly textual type the tool is willing to emit as text.
     *
     * Accepts the whole text/&#42; family plus a curated set of application types that are reliably
     * textual (JSON, XML, JavaScript, XHTML, JSON-LD, form-encoded, and SVG). Anything else
     * (including a null media type) is treated as non-textual and rejected to avoid binary garbage.
     *
     * @param mediaType Lower-cased media type, or null when the header was absent.
     * @return True when the type is considered textual.
     */
    private fun isTextualContentType(mediaType: String?): Boolean {
        if (mediaType == null) return false
        if (mediaType.startsWith("text/")) return true
        return mediaType in TEXTUAL_APPLICATION_TYPES
    }

    /**
     * Resolves a charset name to a [Charset], falling back to UTF-8 when the name is blank, missing,
     * or unsupported by the JVM.
     *
     * @param charsetName Optional charset name from the `Content-Type` header.
     * @return A usable [Charset] (UTF-8 by default).
     */
    private fun resolveCharset(charsetName: String?): Charset {
        if (charsetName.isNullOrBlank()) return Charsets.UTF_8
        return runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
    }

    /**
     * Decodes [bytes] as text using a strict [charset] decoder.
     *
     * Malformed or unmappable input is reported (not silently replaced), so binary payloads fail the
     * decode and are rejected by the caller instead of producing mojibake output.
     *
     * @param bytes Raw response body bytes.
     * @param charset Charset to decode with.
     * @return The decoded text, or null when the bytes are not valid for [charset].
     */
    private fun decodeText(bytes: ByteArray, charset: Charset): String? = runCatching {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private companion object {
        /** Hard cap on the response body bytes the tool will buffer before rejecting the fetch. */
        const val MAX_DOWNLOAD_BYTES: Int = 10 * 1024 * 1024 // 10 MB

        /** Maximum LLM-settable value for the `maxBytes` presentation argument. */
        const val MAX_PRESENTATION_BYTES: Int = 200_000

        /**
         * Number of characters each requested context unit contributes to a long-line match window.
         *
         * Interpreting `contextBefore`/`contextAfter` as `value * 80` characters lets the LLM reveal
         * more of a long (often minified) line than the fixed [SEARCH_MAX_LINE_CHARS] window allows,
         * mirroring the `* 80` per-line budget it already uses for whole-line context.
         */
        const val CONTEXT_CHARS_PER_LINE: Int = 80

        /** Application media types that are reliably textual and safe to emit as text. */
        val TEXTUAL_APPLICATION_TYPES: Set<String> = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/xhtml+xml",
            "application/ld+json",
            "application/x-www-form-urlencoded",
            "image/svg+xml",
        )
    }
}

/**
 * A single rendered line of a web-content search result.
 *
 * @property lineNumber 1-based source line number this line belongs to.
 * @property content The rendered text: either a whole context line or a windowed match snippet.
 * @property isContext True when this is a whole source-line context entry (not a match snippet).
 * @property sourceStart Character offset (into the source line) where a match snippet begins, or 0
 *   for context lines.
 * @property sourceEnd Character offset (into the source line) where a match snippet ends, or 0 for
 *   context lines.
 */
private data class OutputLine(
    val lineNumber: Int,
    val content: String,
    val isContext: Boolean,
    val sourceStart: Int = 0,
    val sourceEnd: Int = 0,
)

/**
 * The resolved render span for a single match occurrence, including the leftover context budget.
 *
 * @property start Character offset (into the source line) where the windowed snippet begins.
 * @property end Character offset (into the source line) where the windowed snippet ends (exclusive).
 * @property beforeLeft Remaining `contextBefore` budget after the window used its share.
 * @property afterLeft Remaining `contextAfter` budget after the window used its share.
 */
private data class MatchWindow(
    val start: Int,
    val end: Int,
    val beforeLeft: Int,
    val afterLeft: Int,
)