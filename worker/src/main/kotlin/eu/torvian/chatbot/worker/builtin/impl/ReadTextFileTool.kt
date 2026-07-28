package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import eu.torvian.chatbot.worker.builtin.validation.addUnknownParameterErrors
import eu.torvian.chatbot.worker.builtin.validation.builtInToolErrorResult
import eu.torvian.chatbot.worker.builtin.validation.invalidInputResult
import eu.torvian.chatbot.worker.builtin.validation.formatTruncationNotice
import eu.torvian.chatbot.worker.builtin.validation.parseOptionalInt
import eu.torvian.chatbot.worker.builtin.validation.parseRequiredString
import eu.torvian.chatbot.worker.builtin.validation.truncateLinesAndBytes
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Reads the contents of a text file inside the worker workspace.
 *
 * Always interprets the file as UTF-8 regardless of extension. The optional `range` parameter
 * selects a half-open `[start, end)` slice of lines using Python slice semantics: indices are
 * 0-based, negative values count from the end, and `null` denotes an open end. When `range` is
 * omitted the entire file is returned.
 */
class ReadTextFileTool : BuiltInTool {
    override val name: String = "read_text_file"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("path", "range", "maxLines", "maxBytes")
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val path = parseRequiredString(input, "path", validationErrors)
        val maxLines = parseOptionalInt(input, "maxLines", defaultValue = 500, validationErrors)
        if (maxLines <= 0) {
            validationErrors.add("Argument 'maxLines' must be > 0")
        }
        val maxBytes = parseOptionalInt(input, "maxBytes", defaultValue = 20000, validationErrors)
        if (maxBytes <= 0) {
            validationErrors.add("Argument 'maxBytes' must be > 0")
        }

        // Parse the optional [start, end) range. Each element is either an integer or null
        // (open-ended), matching the JSON Schema's "type": ["integer", "null"] items.
        val range = input["range"]?.let { element ->
            val array = element as? JsonArray
            if (array == null) {
                validationErrors.add("Argument 'range' must be an array [start, end)")
                null
            } else if (array.size != 2) {
                validationErrors.add("Argument 'range' must contain exactly two elements [start, end)")
                null
            } else {
                val parseBound = { item: JsonElement, label: String ->
                    when (item) {
                        JsonNull -> null
                        !is JsonPrimitive -> {
                            validationErrors.add("Argument '$label' must be an integer or null")
                            null
                        }

                        else -> item.content.toIntOrNull() ?: run {
                            validationErrors.add("Argument '$label' must be an integer or null")
                            null
                        }
                    }
                }
                val start = parseBound(array[0], "range[0]")
                val end = parseBound(array[1], "range[1]")
                if (start == null && end == null) {
                    validationErrors.add("Argument 'range' must specify at least a start or an end")
                    null
                } else {
                    start to end
                }
            }
        }

        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        val target = try {
            WorkspacePathValidator.requireInside(context.workspace, path!!)
        } catch (e: Exception) {
            return builtInToolErrorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        return withContext(context.ioDispatcher) {
            try {
                val allLines = Files.readAllLines(target, Charsets.UTF_8)

                // Resolve the slice bounds with Python semantics: negative indices count from the
                // end, null is open-ended, and the end index is exclusive.
                val (startIdx, endIdx) = resolveSlice(range, allLines.size)
                val selected = allLines.subList(startIdx, endIdx)
                val rawBody = selected.joinToString(separator = "\n")
                val truncationResult = truncateLinesAndBytes(rawBody, maxLines, maxBytes)
                val body = truncationResult.text
                val linesShown = truncationResult.linesShown
                val bytesShown = truncationResult.bytesShown
                val truncated = truncationResult.isTruncated

                // Prefix a single concise header line (relative path + 1-based line range) so the
                // consumer knows which file and lines were read without re-counting the content;
                // keeps token usage low.
                val actualEndIdx = startIdx + linesShown
                val header = buildRangeHeader(path, startIdx, actualEndIdx, allLines.size)
                val content = if (body.isEmpty()) header else "$header\n$body"
                val notice = if (truncated) {
                    formatTruncationNotice(linesShown, bytesShown, "Use 'range' or")
                } else {
                    ""
                }
                val output = content + notice
                val details = buildJsonObject {
                    put("truncated", truncated)
                }
                BuiltInToolExecutionResult(
                    output = output,
                    details = details,
                )
            } catch (_: NoSuchFileException) {
                builtInToolErrorResult(BuiltInToolExecutionError.NOT_FOUND, "File not found: $path")
            } catch (e: Exception) {
                builtInToolErrorResult(BuiltInToolExecutionError.EXECUTION_FAILED, "Failed to read file: ${e.message}")
            }
        }
    }

    /**
     * Resolves a Python-style `[start, end)` slice against a list of [size] lines.
     *
     * Both bounds are clamped to `[0, size]` so out-of-range values behave like Python slicing
     * (which silently truncates rather than throwing). A `null` bound is treated as the natural
     * open end: `0` for start and [size] for end. Negative bounds count backwards from [size].
     *
     * @param range Pair of `(start, end)` where either element may be null; null when no range was supplied.
     * @param size Number of lines in the file.
     * @return A `(startIndex, endIndex)` pair with `0 <= startIndex <= endIndex <= size`.
     */
    private fun resolveSlice(range: Pair<Int?, Int?>?, size: Int): Pair<Int, Int> {
        if (range == null) return 0 to size
        val (rawStart, rawEnd) = range
        val start = when (rawStart) {
            null -> 0
            else -> if (rawStart < 0) (size + rawStart).coerceAtLeast(0) else rawStart.coerceAtMost(size)
        }
        val end = when (rawEnd) {
            null -> size
            else -> if (rawEnd < 0) (size + rawEnd).coerceAtLeast(0) else rawEnd.coerceAtMost(size)
        }
        return start to maxOf(start, end)
    }

    /**
     * Builds a single concise header line describing which 1-based lines were read from [path].
     *
     * The format is `=== <path> (lines:<first>-<last> of <total>) ===` (or a single `<n>` for one
     * line, or `none` when nothing was read). [path] is the workspace-relative path supplied by the
     * caller. This keeps the output token-friendly while telling the consumer exactly which file
     * and lines were returned.
     *
     * @param path Workspace-relative path of the file that was read.
     * @param startIdx 0-based inclusive start index of the slice.
     * @param endIdx 0-based exclusive end index of the slice.
     * @param total Total number of lines in the file.
     * @return The header line.
     */
    private fun buildRangeHeader(path: String, startIdx: Int, endIdx: Int, total: Int): String {
        val count = endIdx - startIdx
        val range = when {
            count <= 0 -> "none"
            count == 1 -> "${startIdx + 1}"
            else -> "${startIdx + 1}-$endIdx"
        }
        return "=== $path (lines:$range of $total) ==="
    }
}