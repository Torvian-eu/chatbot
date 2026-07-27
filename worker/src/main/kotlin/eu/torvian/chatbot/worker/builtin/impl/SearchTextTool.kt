package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

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
 * header line, followed by its matching lines rendered as `<lineNumber>: <content>` (context lines are
 * prefixed with their own line number, without extra indentation). A blank line separates consecutive
 * files, and also separates consecutive matches that carry context. This keeps the path out of every
 * line, making results easier to scan and reducing token usage for the consumer.
 *
 * Matching is **line-based**: a file line contributes at most one result, regardless of how many
 * individual occurrences of the pattern it contains. The result count therefore reflects the number
 * of matching lines, not the number of occurrences.
 */
class SearchTextTool : BuiltInTool {

    /**
     * Maximum file size (in bytes) loaded into memory for searching.
     *
     * The v1 implementation reads each candidate file fully into memory (see [readUtf8Lines]); files
     * larger than this budget are skipped to avoid memory pressure. Streaming/constant-memory search
     * is intentionally out of scope for this step.
     */
    private companion object {
        const val MAX_FILE_BYTES: Long = 1_048_576L // 1 MB
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
            "filePattern", "excludePatterns", "contextBefore", "contextAfter", "maxResults"
        )
        // Check for unknown parameters
        for (key in input.keys) {
            if (key !in validKeys) {
                validationErrors.add("Unknown parameter: '$key'")
            }
        }

        val query = input["query"]?.jsonPrimitive?.content
        if (query == null) {
            validationErrors.add("Missing required argument: query")
        } else if (query.isBlank()) {
            validationErrors.add("Argument 'query' must not be blank")
        }

        val mode = input["mode"]?.jsonPrimitive?.content ?: "plain"
        if (mode !in setOf("plain", "regex")) {
            validationErrors.add("Invalid 'mode' value: $mode (expected 'plain' or 'regex')")
        }

        val caseSensitive = input["caseSensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val wholeWord = input["wholeWord"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        if (mode == "regex" && wholeWord) {
            validationErrors.add("Argument 'wholeWord' is not supported in 'regex' mode")
        }

        val contextBefore = input["contextBefore"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (contextBefore < 0) {
            validationErrors.add("Argument 'contextBefore' must be >= 0")
        }
        val contextAfter = input["contextAfter"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (contextAfter < 0) {
            validationErrors.add("Argument 'contextAfter' must be >= 0")
        }

        val maxResults = input["maxResults"]?.jsonPrimitive?.content?.toIntOrNull()
        if (maxResults != null && maxResults < 1) {
            validationErrors.add("Argument 'maxResults' must be >= 1")
        }

        if (validationErrors.isNotEmpty()) {
            return errorResult(
                BuiltInToolExecutionError.INVALID_INPUT,
                "Input validation failed with ${validationErrors.size} error(s):",
                errorDetails = buildJsonObject {
                    putJsonArray("validationErrors") {
                        validationErrors.forEach { error -> add(error) }
                    }
                }.toString()
            )
        }

        val path = input["path"]?.jsonPrimitive?.content ?: "."
        val filePattern = input["filePattern"]?.jsonPrimitive?.content
        val excludePatterns = when (val excludeInput = input["excludePatterns"]) {
            is JsonArray -> excludeInput.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> excludeInput.contentOrNull?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }

        val root = try {
            WorkspacePathValidator.requireInside(context.workspace, path)
        } catch (e: Exception) {
            return errorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        // Compile the matcher once for the whole search to avoid recompiling per line/file.
        val regex = try {
            compileRegex(query!!, mode, caseSensitive, wholeWord)
        } catch (e: Exception) {
            return errorResult(
                BuiltInToolExecutionError.INVALID_INPUT,
                "Invalid ${if (mode == "regex") "regular expression" else "pattern"}: ${e.message}"
            )
        }

        val fileMatcher = filePattern?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val excludeMatchers = excludePatterns.map { FileSystems.getDefault().getPathMatcher("glob:$it") }

        return withContext(context.ioDispatcher) {
            if (!Files.exists(root)) {
                return@withContext errorResult(BuiltInToolExecutionError.NOT_FOUND, "Path not found: $path")
            }

            // A file is searched directly; a directory is walked recursively for regular files.
            val candidates: List<Path> = if (root.isRegularFile()) {
                listOf(root)
            } else {
                Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() }
            }

            val matchObjects = mutableListOf<JsonObject>()
            // Matches grouped by their start-relative path, preserving first-seen file order so the
            // readable output lists files in the same order they were walked. Grouping lets us emit
            // the path once as a header instead of repeating it on every matching line.
            val matchesByFile = LinkedHashMap<String, MutableList<MatchRender>>()
            var searchedFiles = 0
            var skippedFiles = 0
            var truncated = false

            searchLoop@ for (file in candidates) {
                // Relativize against the starting directory (root) — not the workspace root — so the
                // reported path and the exclude-pattern matching behave intuitively regardless of the
                // absolute filesystem location (e.g. a search rooted at "website/css" reports
                // "style.css" rather than "website/css/style.css"). Normalize to forward slashes so
                // output and exclude-pattern matching are platform-independent (glob matchers expect
                // '/' separators), keeping results consistent across operating systems.
                val relative = root.relativize(file).toString().replace('\\', '/')
                // filePattern filters by the relative path (consistent with SearchFilesTool, which
                // matches the whole start-relative path); excludePatterns also filter by that path.
                if (fileMatcher != null && !fileMatcher.matches(Path.of(relative))) continue@searchLoop
                if (excludeMatchers.any { it.matches(Path.of(relative)) }) continue@searchLoop

                // Skip files that exceed the in-memory budget to avoid memory pressure.
                val size = try {
                    Files.size(file)
                } catch (_: Exception) {
                    -1L
                }
                if (size > MAX_FILE_BYTES) {
                    skippedFiles++
                    continue@searchLoop
                }

                val lines = try {
                    readUtf8Lines(file)
                } catch (_: Exception) {
                    // Conservatively skip files that cannot be read as UTF-8 text (binary, unreadable, etc.).
                    skippedFiles++
                    continue@searchLoop
                }
                searchedFiles++

                for ((index, line) in lines.withIndex()) {
                    // Line-based match: a line contributes at most one result, even when the pattern
                    // occurs multiple times within it.
                    if (!regex.containsMatchIn(line)) continue
                    if (matchObjects.size >= (maxResults ?: Int.MAX_VALUE)) {
                        truncated = true
                        break@searchLoop
                    }
                    val lineNumber = index + 1
                    val beforeStart = maxOf(0, index - contextBefore)
                    val afterEnd = minOf(lines.size, index + 1 + contextAfter)
                    val before = lines.subList(beforeStart, index)
                    val after = lines.subList(index + 1, afterEnd)

                    matchObjects.add(buildJsonObject {
                        put("path", relative)
                        put("lineNumber", lineNumber)
                        put("line", line)
                        putJsonArray("before") { before.forEach { add(it) } }
                        putJsonArray("after") { after.forEach { add(it) } }
                    })

                    // Collect the match for the grouped readable output. The path is emitted once as
                    // a per-file header during rendering (below) rather than repeated on every line,
                    // keeping the textual output compact and token-friendly for the consuming model.
                    val render = MatchRender(
                        lineNumber = lineNumber,
                        line = line,
                        before = before.mapIndexed { bIdx, ctx -> (beforeStart + bIdx + 1) to ctx },
                        after = after.mapIndexed { aIdx, ctx -> (index + 2 + aIdx) to ctx },
                    )
                    matchesByFile.getOrPut(relative) { mutableListOf() }.add(render)
                }
            }

            // Render the grouped readable output: one distinctive header line per file, then each
            // match rendered as "<lineNumber>: <content>" (context lines prefixed with their own line
            // number, without extra indentation). A blank line separates consecutive matches that
            // carry context (before/after), so distinct matches stay easy to tell apart without
            // repeating the path on every line. A blank line also separates consecutive files.
            val outputLines = buildList {
                matchesByFile.forEach { (relative, renders) ->
                    add("=== file: $relative ===")
                    renders.forEachIndexed { idx, render ->
                        // Separate matches that carry context from the previous match for readability.
                        if (idx > 0 && (render.before.isNotEmpty() || render.after.isNotEmpty())) add("")
                        render.before.forEach { (n, ctx) -> add("$n: $ctx") }
                        add("${render.lineNumber}: ${render.line}")
                        render.after.forEach { (n, ctx) -> add("$n: $ctx") }
                    }
                    add("") // separator between files
                }
            }

            val totalMatches = matchObjects.size
            val details = buildJsonObject {
                putJsonArray("matches") { matchObjects.forEach { add(it) } }
                put("totalMatches", totalMatches)
                put("truncated", truncated)
                put("searchedFiles", searchedFiles)
                put("skippedFiles", skippedFiles)
            }
            val summary = buildString {
                append("\n\n")
                append("$totalMatches match(es) across $searchedFiles file(s)")
                if (skippedFiles > 0) append(" ($skippedFiles skipped)")
                if (truncated) append(" — truncated to $maxResults result(s)")
            }

            BuiltInToolExecutionResult(
                output = if (outputLines.isEmpty()) "No matches found" else outputLines.joinToString("\n") + summary,
                details = details,
            )
        }
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

    private fun errorResult(code: String, message: String, errorDetails: String? = null): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code, errorDetails = errorDetails)
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