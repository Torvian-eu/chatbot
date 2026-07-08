package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Performs selective edits inside a text file using `oldText` -> `newText` replacements.
 *
 * Features:
 * - Whitespace-normalized search/replace so minor indentation differences do not cause misses.
 * - Multi-line `oldText` is supported.
 * - Multiple edits are applied in one pass, with correct positioning.
 * - `dryRun` produces a unified diff and match summary without modifying the file.
 */
class EditFileTool : BuiltInTool {
    override val name: String = "edit_file"
    override val description: String = "Apply structured edits to a text file with optional dry-run."
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Path to the file, relative to the workspace.")
            })
            put("edits", buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("oldText", buildJsonObject { put("type", "string") })
                        put("newText", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray { add("oldText"); add("newText") })
                })
            })
            put("dryRun", buildJsonObject {
                put("type", "boolean")
                put("description", "Preview the changes without applying them.")
            })
        })
        put("required", buildJsonArray { add("path"); add("edits") })
    }

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        val path = input["path"]?.jsonPrimitive?.content
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing required argument: path")

        @Suppress("UNCHECKED_CAST")
        val editsJson = input["edits"] as? JsonArray
            ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Missing or invalid 'edits' array")
        val dryRun = input["dryRun"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        val edits = editsJson.mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Edit at index $index is not an object")
            val oldText = obj["oldText"]?.jsonPrimitive?.content
                ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Edit at index $index missing 'oldText'")
            val newText = obj["newText"]?.jsonPrimitive?.content
                ?: return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "Edit at index $index missing 'newText'")
            EditSpec(oldText, newText)
        }

        if (edits.isEmpty()) {
            return errorResult(BuiltInToolExecutionError.INVALID_INPUT, "At least one edit is required")
        }

        val target = try {
            WorkspacePathValidator.requireInside(context.workspace, path)
        } catch (e: Exception) {
            return errorResult(
                BuiltInToolExecutionError.WORKSPACE_VIOLATION,
                e.message ?: "Path rejected by workspace validator"
            )
        }

        return withContext(context.ioDispatcher) {
            val original = try {
                Files.readString(target, Charsets.UTF_8)
            } catch (_: NoSuchFileException) {
                return@withContext errorResult(BuiltInToolExecutionError.NOT_FOUND, "File not found: $path")
            } catch (e: Exception) {
                return@withContext errorResult(
                    BuiltInToolExecutionError.EXECUTION_FAILED,
                    "Failed to read file: ${e.message}"
                )
            }

            val outcome = applyEdits(original, edits)
            if (outcome is ApplyOutcome.Failure) {
                return@withContext errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, outcome.message)
            }
            val success = outcome as ApplyOutcome.Success
            val resultText = if (dryRun) {
                renderDiff(original, success.modified, edits)
            } else {
                try {
                    Files.writeString(target, success.modified, Charsets.UTF_8)
                    renderDiff(original, success.modified, edits)
                } catch (e: Exception) {
                    return@withContext errorResult(
                        BuiltInToolExecutionError.EXECUTION_FAILED,
                        "Failed to write file: ${e.message}"
                    )
                }
            }
            BuiltInToolExecutionResult(output = resultText)
        }
    }

    private fun renderDiff(original: String, modified: String, edits: List<EditSpec>): String {
        val sb = StringBuilder()
        sb.append("Applied ").append(edits.size).append(" edit(s)\n")
        sb.append("--- diff ---\n")
        val originalLines = original.split('\n')
        val modifiedLines = modified.split('\n')
        val max = maxOf(originalLines.size, modifiedLines.size)
        for (i in 0 until max) {
            val orig = originalLines.getOrNull(i)
            val mod = modifiedLines.getOrNull(i)
            when {
                orig == null -> sb.append("+ ").append(mod).append('\n')
                mod == null -> sb.append("- ").append(orig).append('\n')
                orig != mod -> {
                    sb.append("- ").append(orig).append('\n')
                    sb.append("+ ").append(mod).append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun errorResult(code: String, message: String): BuiltInToolExecutionResult =
        BuiltInToolExecutionResult(isError = true, errorMessage = message, errorCode = code)

    private data class EditSpec(val oldText: String, val newText: String)

    private sealed interface ApplyOutcome {
        data class Success(val modified: String) : ApplyOutcome
        data class Failure(val message: String) : ApplyOutcome
    }

    /**
     * Applies all edits sequentially to the input text, returning the modified text or a failure
     * description. The search is whitespace-normalized so leading/trailing whitespace differences
     * do not cause spurious misses.
     */
    private fun applyEdits(text: String, edits: List<EditSpec>): ApplyOutcome {
        var current = text
        edits.forEachIndexed { index, edit ->
            val occurrenceIndex = findNormalizedIndex(current, edit.oldText)
            if (occurrenceIndex < 0) {
                return ApplyOutcome.Failure("Edit at index $index: 'oldText' not found (after whitespace normalization)")
            }
            current = current.substring(0, occurrenceIndex) +
                    edit.newText +
                    current.substring(occurrenceIndex + edit.oldText.length)
        }
        return ApplyOutcome.Success(current)
    }

    /**
     * Finds the first occurrence of [needle] in [haystack] after both strings are
     * whitespace-normalized (leading/trailing whitespace on each line collapsed).
     *
     * Returns the byte offset within the **original** [haystack] of the matched region.
     */
    private fun findNormalizedIndex(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        val haystackNorm = normalize(haystack)
        val needleNorm = normalize(needle)
        val idx = haystackNorm.indexOf(needleNorm)
        if (idx < 0) return -1
        // Map back to original offset: count characters of `haystack` that correspond to the
        // prefix. The normalized strings are produced by joining words with single spaces and
        // collapsing runs of whitespace, so a positional map is straightforward: scan the
        // original and produce the same normalization tokens to find the matching boundary.
        return originalOffsetOf(haystack, idx, needleNorm)
    }

    /**
     * Maps a character offset in the normalized [haystack] back to a character offset in the
     * original [haystack].
     */
    private fun originalOffsetOf(haystack: String, normOffset: Int, needleNorm: String): Int {
        var normCursor = 0
        var origCursor = 0
        var origStart = -1
        var origEnd = -1
        var lastWasSpace = true
        while (origCursor < haystack.length) {
            val c = haystack[origCursor]
            val isWs = c.isWhitespace()
            if (isWs) {
                if (!lastWasSpace) {
                    normCursor++
                    lastWasSpace = true
                }
            } else {
                if (lastWasSpace) {
                    if (normCursor == normOffset) {
                        origStart = origCursor
                    }
                    lastWasSpace = false
                }
                normCursor++
            }
            if (normCursor >= normOffset + needleNorm.length && origEnd < 0) {
                origEnd = origCursor + 1
                break
            }
            origCursor++
        }
        if (origEnd < 0) origEnd = haystack.length
        return origStart.coerceAtLeast(0)
    }

    /**
     * Normalizes a string for whitespace-insensitive comparison: collapses all whitespace to a
     * single space and trims leading/trailing whitespace.
     */
    private fun normalize(input: String): String =
        input.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}
