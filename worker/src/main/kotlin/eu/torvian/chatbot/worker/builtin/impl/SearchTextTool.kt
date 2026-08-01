package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import eu.torvian.chatbot.worker.builtin.validation.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.milliseconds

/**
 * Searches UTF-8 text content inside the worker workspace for matching text or regex patterns.
 *
 * The starting `path` may be a file (searched directly) or a directory (searched recursively).
 * Matching supports plain-text and regular-expression modes, optional case-insensitivity, and
 * optional whole-word anchoring (plain mode only). Candidate files can be narrowed with a
 * `filePattern` glob (matched against the path relative to the starting directory) and excluded
 * with `excludePatterns` globs (matched against the same relative path).
 *
 * Files that cannot be read as UTF-8 text (for example binary files) are skipped conservatively
 * rather than failing the whole search. The result carries both a grep-like textual [BuiltInToolExecutionResult.output]
 * and a structured `details` object with per-match context.
 *
 * The textual output is **grouped by file**: each file contributes a distinctive `=== file: <path> ===`
 * header line, followed by matching and context lines rendered as `<lineNumber>: <content>` (context
 * lines are prefixed with their own line number, without extra indentation). Overlapping context
 * windows are compacted so each source line is emitted at most once; matching lines take precedence
 * over context renderings. A blank line separates consecutive files and disjoint rendered ranges.
 * This keeps the path out of every line, makes results easier to scan, and reduces token usage for the
 * consumer.
 *
 * Matching is **line-based**: a file line contributes at most one result, regardless of how many
 * individual occurrences of the pattern it contains. The result count therefore reflects the number
 * of matching lines, not the number of occurrences.
 *
 * The search is bounded by a configurable `timeout` (defaulting to [BuiltInToolExecutionContext.defaultSearchTimeoutSeconds]).
 * When the timeout fires, partial results found so far are returned with a timeout notice rather
 * than failing with an error. The elapsed search time is always reported in the output to help the
 * LLM self-correct on subsequent calls.
 */
class SearchTextTool : BuiltInTool {

    private companion object {
        /**
         * Maximum file size (in bytes) loaded into memory for searching.
         *
         * The v1 implementation reads each candidate file fully into memory (see [readUtf8Lines]); files
         * larger than this budget are skipped to avoid memory pressure. Streaming/constant-memory search
         * is intentionally out of scope for this step.
         */
        const val MAX_FILE_BYTES: Long = 1_048_576L // 1 MB

        /**
         * Maximum number of characters allowed per line in search output.
         */
        const val MAX_LINE_CHARS = 200

    }

    override val name: String = "search_text"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf(
            "path", "query", "mode", "caseSensitive", "wholeWord",
            "filePattern", "excludePatterns", "timeout", "contextBefore", "contextAfter", "maxResults", "maxBytes"
        )
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val query = parseRequiredString(input, "query", validationErrors)
        if (query != null && query.isBlank()) {
            validationErrors.add("Argument 'query' must not be blank")
        }

        // Default mode is "regex"
        val mode = parseOptionalString(input, "mode", validationErrors) ?: "regex"
        if (mode !in setOf("plain", "regex")) {
            validationErrors.add("Invalid 'mode' value: $mode (expected 'plain' or 'regex')")
        }

        // Optional boolean fields: validate if present, otherwise use defaults
        val caseSensitive = parseOptionalBoolean(input, "caseSensitive", defaultValue = false, validationErrors)
        val wholeWord = parseOptionalBoolean(input, "wholeWord", defaultValue = false, validationErrors)
        if (mode == "regex" && wholeWord) {
            validationErrors.add("Argument 'wholeWord' is not supported in 'regex' mode")
        }

        // Optional integer fields: validate if present, otherwise use defaults
        val contextBefore = parseOptionalInt(input, "contextBefore", defaultValue = 0, validationErrors)
        if (contextBefore < 0) {
            validationErrors.add("Argument 'contextBefore' must be >= 0")
        }
        val contextAfter = parseOptionalInt(input, "contextAfter", defaultValue = 0, validationErrors)
        if (contextAfter < 0) {
            validationErrors.add("Argument 'contextAfter' must be >= 0")
        }

        val maxResults = parseOptionalInt(input, "maxResults", defaultValue = 10, validationErrors)
        if (maxResults < 1) {
            validationErrors.add("Argument 'maxResults' must be >= 1")
        }

        val maxBytes = parseOptionalInt(input, "maxBytes", defaultValue = 1200, validationErrors)
        if (maxBytes <= 0) {
            validationErrors.add("Argument 'maxBytes' must be > 0")
        }
        val path = parseRequiredString(input, "path", validationErrors)
        val filePattern = parseOptionalString(input, "filePattern", validationErrors)
        val excludePatterns = parseStringOrStringArray(input, "excludePatterns", validationErrors)
        val timeoutSeconds = parseOptionalLong(
            input, "timeout",
            defaultValue = context.defaultSearchTimeoutSeconds,
            validationErrors
        )
        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        val root = try {
            WorkspacePathValidator.requireInside(context.workspace, path!!)
        } catch (e: Exception) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        // Compile the matcher once for the whole search to avoid recompiling per line/file.
        val regex = try {
            compileRegex(query!!, mode, caseSensitive, wholeWord)
        } catch (e: Exception) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.INVALID_INPUT,
                "Invalid ${if (mode == "regex") "regular expression" else "pattern"}: ${e.message}"
            )
        }

        val fileMatcher = filePattern?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val excludeMatchers = excludePatterns.map { FileSystems.getDefault().getPathMatcher("glob:$it") }

        return withContext(context.ioDispatcher) {
            if (!Files.exists(root)) {
                return@withContext builtInToolErrorResult(BuiltInToolExecutionError.NOT_FOUND, "Path not found: $path")
            }

            val startNanos = System.nanoTime()

            val matchObjects = mutableListOf<JsonObject>()
            // Matches grouped by their start-relative path, preserving first-seen file order so the
            // readable output lists files in the same order they were walked. Grouping lets us emit
            // the path once as a header instead of repeating it on every matching line.
            val matchesByFile = LinkedHashMap<String, MutableList<MatchRender>>()
            var searchedFiles = 0
            var skippedFiles = 0
            var truncatedByMaxResults = false
            var truncatedByTimeout = false
            try {
                withTimeout((timeoutSeconds * 1000L).milliseconds) {
                    // A file is searched directly; a directory is walked recursively for regular files.
                    // Collect candidate files first (fast walk), then iterate with yield() between files
                    // so that withTimeout can cancel the search when it exceeds the time budget.
                    val candidates: List<Path> = if (root.isRegularFile()) {
                        listOf(root)
                    } else {
                        Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() }
                    }

                    for (file in candidates) {
                        yield() // allow coroutine cancellation for timeout
                        // Use paths relative to the search root, normalize separators to /, and fall back to the file name
                        // when the root is a single file so results stay consistent and meaningful across platforms.
                        val relative = if (root.isRegularFile()) {
                            root.fileName.toString().replace('\\', '/')
                        } else {
                            root.relativize(file).toString().replace('\\', '/')
                        }
                        // filePattern filters by the relative path (consistent with SearchFilesTool, which
                        // matches the whole start-relative path); excludePatterns also filter by that path.
                        if (fileMatcher != null && !fileMatcher.matches(Path.of(relative))) continue
                        if (excludeMatchers.any { it.matches(Path.of(relative)) }) continue

                        // Skip files that exceed the in-memory budget to avoid memory pressure.
                        val size = try {
                            Files.size(file)
                        } catch (_: Exception) {
                            -1L
                        }
                        if (size > MAX_FILE_BYTES) {
                            skippedFiles++
                            continue
                        }

                        val lines = try {
                            readUtf8Lines(file)
                        } catch (_: Exception) {
                            // Conservatively skip files that cannot be read as UTF-8 text (binary, unreadable, etc.).
                            skippedFiles++
                            continue
                        }
                        searchedFiles++

                        for ((index, line) in lines.withIndex()) {
                            // Line-based match: a line contributes at most one result, even when the pattern
                            // occurs multiple times within it.
                            val matchResult = regex.find(line) ?: continue
                            if (matchObjects.size >= maxResults) {
                                truncatedByMaxResults = true
                                break
                            }
                            val matchRange = matchResult.range
                            val lineNumber = index + 1
                            val beforeStart = maxOf(0, index - contextBefore)
                            val afterEnd = minOf(lines.size, index + 1 + contextAfter)
                            val before = lines.subList(beforeStart, index)
                            val after = lines.subList(index + 1, afterEnd)

                            val trimmedBeforeList = before.map { trimContext(it) }
                            val trimmedAfterList = after.map { trimContext(it) }
                            val windowedLine = windowLineAroundMatch(line, matchRange)

                            matchObjects.add(buildJsonObject {
                                put("path", relative)
                                put("lineNumber", lineNumber)
                                put("line", windowedLine)
                                putJsonArray("before") { trimmedBeforeList.forEach { add(it) } }
                                putJsonArray("after") { trimmedAfterList.forEach { add(it) } }
                            })

                            // Collect the match for the grouped readable output. The path is emitted once as
                            // a per-file header during rendering (below) rather than repeated on every line,
                            // keeping the textual output compact and token-friendly for the consuming model.
                            val render = MatchRender(
                                lineNumber = lineNumber,
                                line = windowedLine,
                                before = before.mapIndexed { bIdx, ctx -> (beforeStart + bIdx + 1) to trimContext(ctx) },
                                after = after.mapIndexed { aIdx, ctx -> (index + 2 + aIdx) to trimContext(ctx) },
                            )
                            matchesByFile.getOrPut(relative) { mutableListOf() }.add(render)
                        }
                        // Check if we need to break due to maxResults before walking the next file
                        if (truncatedByMaxResults) break
                    }
                }
            } catch (_: TimeoutCancellationException) {
                truncatedByTimeout = true
            }

            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            // Render one compact, source-ordered range per file. Per-match details remain unchanged;
            // only the human-readable projection merges overlapping context windows.
            val outputLines = buildList {
                matchesByFile.forEach { (relative, renders) ->
                    add("=== file: $relative ===")
                    val compactedLines = compactRenders(renders)
                    compactedLines.forEachIndexed { index, renderedLine ->
                        // A gap means the context windows are disjoint; retain a visual separator there,
                        // but do not add one merely because two match records overlap.
                        if (index > 0 && renderedLine.lineNumber != compactedLines[index - 1].lineNumber + 1) {
                            add("")
                        }
                        add("${renderedLine.lineNumber}: ${renderedLine.text}")
                    }
                    add("") // separator between files
                }
            }

            val rawOutput = if (outputLines.isEmpty()) "" else outputLines.joinToString("\n")
            val truncationResult = truncateLinesAndBytes(rawOutput, maxLines = Int.MAX_VALUE, maxBytes = maxBytes)
            val truncatedOutput = truncationResult.text
            val linesShown = truncationResult.linesShown
            val bytesShown = truncationResult.bytesShown
            val truncated = truncatedByMaxResults || truncatedByTimeout || truncationResult.isTruncated

            val totalMatches = matchObjects.size
            val details = buildJsonObject {
                putJsonArray("matches") { matchObjects.forEach { add(it) } }
                put("totalMatches", totalMatches)
                put("truncated", truncated)
                put("searchedFiles", searchedFiles)
                put("skippedFiles", skippedFiles)
            }

            // Build the summary and hints
            val summary = buildString {
                append("\n\n")
                append("$totalMatches match(es) across $searchedFiles file(s)")
                if (skippedFiles > 0) append(" ($skippedFiles skipped)")
                if (truncatedByMaxResults) append(" — truncated to $maxResults result(s)")
            }

            val timeoutNotice = if (truncatedByTimeout) {
                "\n\n[Search timed out after ${elapsedMs}ms — showing $totalMatches match(es) from $searchedFiles file(s). " +
                        "Use a more specific 'path' or increase 'timeout' to search further.]"
            } else {
                ""
            }

            val truncationNotice = if (truncated && !truncatedByTimeout) {
                formatTruncationNotice(linesShown, bytesShown, "Increase 'maxResults'/'maxBytes' to read further.")
            } else {
                ""
            }

            val hints = mutableListOf<String>()
            // Add duration feedback or slow-search hint, never both
            if (elapsedMs > 1000) {
                hints += if (elapsedMs > 3000) {
                    "Hint: This search took ${elapsedMs}ms. To speed up future searches, " +
                            "specify a more specific 'path' to narrow the search scope."
                } else {
                    "[Search completed in ${elapsedMs}ms]"
                }
            }

            // Add hint when no matches found in plain mode but query looks like regex
            if (totalMatches == 0 && mode == "plain" && looksLikeRegex(query)) {
                hints += "Hint: Your query appears to be a regular expression. If you want to use regex matching, set mode='regex'."
            }

            // Add hint when no matches found and filePattern looks like a non-recursive top-level pattern with subdirectories present
            if (totalMatches == 0 && filePattern != null && Files.isDirectory(root)
                && looksLikeNonRecursiveTopLevelPattern(filePattern) && hasSubdirectories(root)
            ) {
                val suggested = toRecursiveHintPattern(filePattern)
                hints += "Hint: filePattern='$filePattern' only matches files in the starting directory." +
                        " If you intended to search recursively, use filePattern='$suggested'."
            }

            // Add hint when filePattern starts with **/ (regardless of whether matches were found or not)
            if (filePattern != null && isUnintentionalLeadingSlashStarStar(filePattern)) {
                hints += "Hint: filePattern='$filePattern' excludes files directly in the starting directory" +
                        " because '**/ ' requires a directory separator. To include files in the starting" +
                        " directory as well, use filePattern='${fixLeadingSlashStarStar(filePattern)}'."
            }

            val hintSuffix = if (hints.isEmpty()) "" else "\n\n" + hints.joinToString("\n\n")

            BuiltInToolExecutionResult(
                output = if (outputLines.isEmpty()) {
                    "No matches found$timeoutNotice$hintSuffix"
                } else {
                    truncatedOutput + summary + timeoutNotice + truncationNotice + hintSuffix
                },
                details = details,
            )
        }
    }

    /**
     * Compacts per-match renderings into a unique, source-ordered set of lines for readable output.
     *
     * Context entries are inserted only when their source line has not already been seen. Matching
     * entries always replace an existing context entry, ensuring a line that is itself a match keeps
     * its match-centered rendering rather than a potentially shortened context rendering.
     *
     * @param renders Per-match renderings belonging to one file, in match discovery order.
     * @return Unique rendered lines sorted by their 1-based source line number.
     */
    private fun compactRenders(renders: List<MatchRender>): List<RenderedLine> {
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

    /**
     * Checks if the query appears to be a regular expression pattern.
     *
     * This is an intentionally coarse heuristic used only for deciding whether to show
     * a helpful hint when a plain-text search finds no matches. It is not a regex parser
     * and does not try to classify every edge case perfectly.
     *
     * @param query The search query to analyze.
     * @return True if the query looks like it contains regex patterns.
     */
    private fun looksLikeRegex(query: String): Boolean {
        if (query.isBlank()) return false

        // Check for common regex escape sequences that are unlikely to be used in plain text.
        val regexEscapes = listOf("\\d", "\\D", "\\w", "\\W", "\\s", "\\S", "\\b", "\\B")
        if (regexEscapes.any(query::contains)) return true

        // Check for anchors and alternation
        if (query.startsWith("^") || query.endsWith("$") || "|" in query) return true

        // Check for character classes like [a-z] or [^0-9]
        if (Regex("""(?<!\\)\[[^]]+]""").containsMatchIn(query)) return true

        // Check for quantifiers like *, +, ?, {n}, {n,}, {n,m}
        if (Regex("""(?<!\\)[\w)\]](?:[+*?]|\{\d+(,\d*)?})""").containsMatchIn(query)) return true

        return false
    }

    /**
     * Builds the [Regex] used to test each line.
     *
     * In plain mode the [query] is treated literally (escaped) and, when [wholeWord] is set, wrapped
     * in word boundaries. In regex mode the [query] is used verbatim. Case-insensitivity is applied
     * unless [caseSensitive] is true.
     *
     * @param query Raw query from the tool input.
     * @param mode Either `"plain"` or `"regex"`.
     * @param caseSensitive When false, matching ignores case.
     * @param wholeWord When true (plain mode only), matches are anchored to word boundaries.
     * @return Compiled [Regex] for line matching.
     * @throws IllegalArgumentException If the regex pattern is invalid.
     */
    private fun compileRegex(query: String, mode: String, caseSensitive: Boolean, wholeWord: Boolean): Regex {
        val pattern = if (mode == "regex") {
            query
        } else {
            if (wholeWord) "\\b${Regex.escape(query)}\\b" else Regex.escape(query)
        }
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(pattern, options)
    }

    /**
     * Reads [file] as UTF-8 text, splitting it into lines.
     *
     * Uses a strict decoder that reports (rather than silently replaces) malformed input, so files
     * that are not valid UTF-8 text (for example binaries) throw and are skipped by the caller
     * instead of producing mojibake output. Line terminators (LF, CRLF, and CR) are stripped, so
     * matched lines never contain a trailing carriage return.
     *
     * @param file Regular file to read.
     * @return Lines of the file, without line terminators.
     * @throws CharacterCodingException If the file is not valid UTF-8 text.
     */
    private fun readUtf8Lines(file: Path): List<String> {
        val bytes = Files.readAllBytes(file)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        // Split on any of the common line terminators. CRLF is tried first so it is consumed as a
        // single terminator (no stray '\r' left behind); lone CR or LF are handled uniformly.
        return text.split(Regex("\r\n|\r|\n"))
    }

    /**
     * Windows a matching line around [matchRange] if it exceeds [maxChars], ensuring the match is visible.
     *
     * @param line The raw line string to be windowed.
     * @param matchRange The character index range where the match was found.
     * @param maxChars Maximum character limit for the windowed line.
     * @return The windowed line snippet with ellipsis markers where truncated.
     */
    private fun windowLineAroundMatch(
        line: String,
        matchRange: IntRange,
        maxChars: Int = MAX_LINE_CHARS,
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
     * Trims a context line if it exceeds [MAX_LINE_CHARS], appending an ellipsis suffix.
     *
     * @param ctx The context line string.
     * @return The trimmed context line if too long, or the original line otherwise.
     */
    private fun trimContext(ctx: String): String =
        if (ctx.length > MAX_LINE_CHARS) ctx.take(MAX_LINE_CHARS) + "..." else ctx

}

/**
 * Renderable representation of a single matching line, grouped later by file.
 *
 * The path is intentionally omitted here: it is emitted once as a per-file header during
 * rendering (see the grouped-output block in [SearchTextTool.execute]) rather than repeated on every line,
 * which keeps the textual output compact and token-friendly for the consuming model.
 *
 * @property lineNumber 1-based line number of the match within its file.
 * @property line Content of the matching line.
 * @property before Context lines preceding the match, each paired with its 1-based line number.
 * @property after Context lines following the match, each paired with its 1-based line number.
 */
private data class MatchRender(
    val lineNumber: Int,
    val line: String,
    val before: List<Pair<Int, String>>,
    val after: List<Pair<Int, String>>,
)

/**
 * Represents one unique source line in the compact readable rendering of a file.
 *
 * @property lineNumber 1-based source line number used as the deduplication key.
 * @property text Rendered source content, either context-trimmed or match-centered.
 */
private data class RenderedLine(
    val lineNumber: Int,
    val text: String,
)