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
 * Behavior:
 * - Matching is performed in **whitespace-normalized** space so minor indentation differences do
 *   not cause misses, but every replacement is applied using the **original-space** character
 *   range that the normalized match maps back to. This keeps the fix where replacements never rely
 *   on `oldText.length` for normalized matches.
 * - Edits are planned as a batch against the *same* original text. The planning phase never mutates
 *   the source, so caller-supplied order does not influence which ranges are found.
 * - Conflicts are resolved deterministically: when two planned edits overlap, the **more specific**
 *   edit wins (longer matched original span first; ties broken by lower original edit index). The
 *   lower-priority overlapping edit is rejected with a clear summary rather than silently dropped.
 * - Accepted edits are applied in **reverse start-index order** against the original text, which
 *   avoids offset shifting and preserves the planned ranges.
 * - `dryRun` produces a unified diff plus a summary (requested/matched/applied/rejected) without
 *   modifying the file.
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

            // Plan + resolve conflicts against the original text (no mutation yet).
            val plan = planAndResolve(original, edits)
            if (plan is PlanResult.Failure) {
                return@withContext errorResult(BuiltInToolExecutionError.EXECUTION_FAILED, plan.message)
            }
            val success = plan as PlanResult.Success

            // Apply accepted edits in reverse start-index order against the original text.
            val modified = applyAccepted(original, success.accepted)

            val report = renderReport(original, modified, edits, success)
            if (dryRun) {
                BuiltInToolExecutionResult(output = report)
            } else {
                try {
                    Files.writeString(target, modified, Charsets.UTF_8)
                    BuiltInToolExecutionResult(output = report)
                } catch (e: Exception) {
                    return@withContext errorResult(
                        BuiltInToolExecutionError.EXECUTION_FAILED,
                        "Failed to write file: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * One requested edit as supplied by the caller (before any matching/planning).
     *
     * @property oldText Text to locate (matched in normalized space).
     * @property newText Replacement text.
     */
    private data class EditSpec(val oldText: String, val newText: String)

    /**
     * Range of a match in the original text, covering start (inclusive) to end (exclusive).
     *
     * @property startIndex Start offset (inclusive) in the original string.
     * @property endIndexExclusive End offset (exclusive) in the original string.
     */
    private data class MatchRange(val startIndex: Int, val endIndexExclusive: Int)

    /**
     * A planned edit after its match has been located in the original text.
     *
     * The [range] is expressed in original-space coordinates and [matchedSpanLength] is the length
     * of that original span. Both are used for conflict resolution and for applying the edit.
     *
     * @property index Original caller-supplied edit index (for deterministic tie-breaking and reporting).
     * @property oldText Text that was matched.
     * @property newText Replacement text.
     * @property range Original-space range of the matched region.
     * @property matchedSpanLength Length of [range] (`endIndexExclusive - startIndex`).
     */
    private data class PlannedEdit(
        val index: Int,
        val oldText: String,
        val newText: String,
        val range: MatchRange,
        val matchedSpanLength: Int
    )

    /**
     * An edit that matched the original text but was rejected during conflict resolution.
     *
     * @property index Original caller-supplied edit index.
     * @property reason Human-readable explanation (typically which higher-priority edit it overlapped).
     */
    private data class RejectedEdit(val index: Int, val reason: String)

    private sealed interface PlanResult {
        data class Failure(val message: String) : PlanResult
        data class Success(val accepted: List<PlannedEdit>, val rejected: List<RejectedEdit>) : PlanResult
    }

    /**
     * Plans every edit against the same [text] and resolves overlapping matches deterministically.
     *
     * Planning never mutates [text]: each edit is matched independently against the original string,
     * so caller order cannot change which ranges are found. If any edit cannot be matched, the
     * whole operation fails (as before) naming the failing edit index.
     *
     * Overlap policy: when two planned edits overlap, the **more specific** edit wins — the one
     * with the longer matched original span. Ties (equal span length) are broken by the lower
     * original edit index for determinism. The lower-priority overlapping edit is rejected (kept in
     * the result summary) rather than silently dropped, and the higher-priority edit is applied.
     *
     * @param text Original file content (unmodified).
     * @param edits Caller-supplied edits in their original order.
     * @return A [PlanResult.Failure] if any edit cannot be matched, otherwise a [PlanResult.Success]
     *   carrying the accepted and rejected edits.
     */
    private fun planAndResolve(text: String, edits: List<EditSpec>): PlanResult {
        val planned = edits.mapIndexed { index, edit ->
            val range = findNormalizedRange(text, edit.oldText)
                ?: return PlanResult.Failure(
                    "Edit at index $index: 'oldText' not found (after whitespace normalization)"
                )
            PlannedEdit(
                index = index,
                oldText = edit.oldText,
                newText = edit.newText,
                range = range,
                matchedSpanLength = range.endIndexExclusive - range.startIndex
            )
        }

        // Resolve conflicts by priority: longer matched span first, then lower original index.
        val byPriority = planned.sortedWith(
            compareByDescending<PlannedEdit> { it.matchedSpanLength }.thenBy { it.index }
        )
        val accepted = mutableListOf<PlannedEdit>()
        val rejected = mutableListOf<RejectedEdit>()
        for (candidate in byPriority) {
            val conflict = accepted.firstOrNull { overlaps(it.range, candidate.range) }
            if (conflict != null) {
                // Lower-priority overlapping edit is rejected; the higher-priority one is kept.
                rejected.add(
                    RejectedEdit(
                        index = candidate.index,
                        reason = "Overlaps edit at index ${conflict.index} (kept higher-priority edit)"
                    )
                )
            } else {
                accepted.add(candidate)
            }
        }
        return PlanResult.Success(accepted, rejected)
    }

    /**
     * Applies the accepted edits to [text] in reverse start-index order.
     *
     * Because all [PlannedEdit] ranges are disjoint (overlaps were rejected) and computed against
     * the original [text], applying from the highest start index downward keeps every earlier range
     * valid — no offset shifting occurs between edits.
     *
     * @param text Original file content.
     * @param accepted Edits accepted by [planAndResolve], in any order.
     * @return The modified text with all accepted edits applied.
     */
    private fun applyAccepted(text: String, accepted: List<PlannedEdit>): String {
        val ordered = accepted.sortedByDescending { it.range.startIndex }
        var result = text
        for (edit in ordered) {
            val r = edit.range
            result = result.substring(0, r.startIndex) + edit.newText + result.substring(r.endIndexExclusive)
        }
        return result
    }

    /**
     * Renders a human-readable report: a summary of requested/matched/applied/rejected edits
     * followed by a unified diff of the applied changes.
     *
     * @param original Original file content.
     * @param modified File content after accepted edits were applied.
     * @param edits All caller-supplied edits (for the requested count).
     * @param plan The resolved plan (accepted + rejected edits).
     */
    private fun renderReport(
        original: String,
        modified: String,
        edits: List<EditSpec>,
        plan: PlanResult.Success
    ): String {
        val matched = plan.accepted.size + plan.rejected.size
        val sb = StringBuilder()
        sb.append("Edit summary:\n")
        sb.append("- requested: ").append(edits.size).append('\n')
        sb.append("- matched: ").append(matched).append('\n')
        sb.append("- applied: ").append(plan.accepted.size).append('\n')
        sb.append("- rejected: ").append(plan.rejected.size).append('\n')
        if (plan.rejected.isNotEmpty()) {
            sb.append("Rejected edits (overlapping, lower priority):\n")
            for (r in plan.rejected) {
                sb.append("  - index ").append(r.index).append(": ").append(r.reason).append('\n')
            }
        }
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

    /**
     * Returns `true` when two original-space ranges overlap (share at least one character).
     *
     * Two half-open ranges `[aStart, aEnd)` and `[bStart, bEnd)` overlap iff
     * `aStart < bEnd && bStart < aEnd`.
     */
    private fun overlaps(a: MatchRange, b: MatchRange): Boolean =
        a.startIndex < b.endIndexExclusive && b.startIndex < a.endIndexExclusive

    /**
     * Finds the first occurrence of [needle] in [haystack] after both strings are
     * whitespace-normalized (leading/trailing whitespace on each line collapsed).
     *
     * @return The [MatchRange] within the **original** [haystack] of the matched region,
     *   or `null` if the needle is not found after normalization.
     */
    private fun findNormalizedRange(haystack: String, needle: String): MatchRange? {
        if (needle.isEmpty()) return MatchRange(0, 0)
        val haystackNorm = normalize(haystack)
        val needleNorm = normalize(needle)
        val idx = haystackNorm.indexOf(needleNorm)
        if (idx < 0) return null
        return originalRangeOf(haystack, idx, needleNorm)
    }

    /**
     * Maps a character offset range in the normalized [haystack] back to the corresponding
     * [MatchRange] in the original [haystack].
     *
     * The normalized strings are produced by collapsing runs of whitespace to single spaces and
     * trimming. This function scans the original string, reconstructs the normalized token
     * boundaries, and records both the first and last original character that correspond to the
     * normalized token span [normOffset, normOffset + needleNorm.length).
     */
    private fun originalRangeOf(haystack: String, normOffset: Int, needleNorm: String): MatchRange {
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
            if (normCursor >= normOffset + needleNorm.length) {
                // The normalized match ends here — capture the position *after* the last
                // consumed character in the original. For whitespace runs this includes all
                // consecutive whitespace characters that collapsed into the trailing space
                // token in the normalized form.
                origEnd = origCursor + 1
                break
            }
            origCursor++
        }
        // If the match extends exactly to the string end, the loop exits before setting origEnd.
        if (origEnd < 0) origEnd = haystack.length
        return MatchRange(origStart.coerceAtLeast(0), origEnd)
    }

    /**
     * Normalizes a string for whitespace-insensitive comparison: collapses all whitespace to a
     * single space and trims leading/trailing whitespace.
     */
    private fun normalize(input: String): String =
        input.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}
