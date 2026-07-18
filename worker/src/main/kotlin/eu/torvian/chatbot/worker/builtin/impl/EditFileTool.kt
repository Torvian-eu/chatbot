package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.WorkspacePathValidator
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * - Each caller-supplied edit spec matches **all** of its non-overlapping occurrences in the
 *   original text (not just the first). One edit spec may therefore produce multiple planned
 *   occurrences.
 * - Edits are planned as a single global batch against the *same* original text. The planning phase
 *   never mutates the source, so caller-supplied order does not influence which ranges are found.
 * - Conflicts are resolved deterministically across **all** occurrences: when two planned
 *   occurrences overlap, the **more specific** occurrence wins (longer matched original span first;
 *   ties broken by lower original edit index, then by earlier start index). The lower-priority
 *   overlapping occurrence is rejected with a clear summary rather than silently dropped.
 * - Accepted occurrences are applied in **reverse start-index order** against the original text,
 *   which avoids offset shifting and preserves the planned ranges.
 * - `dryRun` produces a unified diff plus a summary (requested edit specs / matched / applied /
 *   rejected occurrences) without modifying the file.
 * - If an edit spec matches zero occurrences, the whole operation fails naming that edit spec's
 *   index.
 */
class EditFileTool : BuiltInTool {
    override val name: String = "edit_file"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

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
            if (oldText.isBlank()) {
                return errorResult(
                    BuiltInToolExecutionError.INVALID_INPUT,
                    "Edit at index $index has empty or whitespace-only 'oldText'"
                )
            }
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
     * A single planned occurrence of a caller-supplied edit spec after its match has been located
     * in the original text.
     *
     * One [EditSpec] may produce several [PlannedEditOccurrence]s (one per non-overlapping match in
     * the original text). The [range] is expressed in original-space coordinates and
     * [matchedSpanLength] is the length of that original span. Both are used for conflict resolution
     * and for applying the occurrence.
     *
     * @property index Original caller-supplied edit spec index (for deterministic tie-breaking and reporting).
     * @property oldText Text that was matched.
     * @property newText Replacement text.
     * @property range Original-space range of the matched region.
     * @property matchedSpanLength Length of [range] (`endIndexExclusive - startIndex`).
     */
    private data class PlannedEditOccurrence(
        val index: Int,
        val oldText: String,
        val newText: String,
        val range: MatchRange,
        val matchedSpanLength: Int
    )

    /**
     * An occurrence that matched the original text but was rejected during conflict resolution.
     *
     * @property index Original caller-supplied edit spec index of the rejected occurrence.
     * @property reason Human-readable explanation identifying the kept conflicting occurrence
     *   (its edit spec index and original-space range).
     */
    private data class RejectedEdit(val index: Int, val reason: String)

    private sealed interface PlanResult {
        data class Failure(val message: String) : PlanResult
        data class Success(val accepted: List<PlannedEditOccurrence>, val rejected: List<RejectedEdit>) : PlanResult
    }

    /**
     * Plans every occurrence of every edit spec against the same [text] and resolves overlapping
     * matches deterministically across all occurrences.
     *
     * Planning never mutates [text]: each edit spec is matched independently against the original
     * string, and **all** of its non-overlapping occurrences are collected, so caller order cannot
     * change which ranges are found. If any edit spec produces zero matches, the whole operation
     * fails naming that edit spec's index.
     *
     * Overlap policy: when two planned occurrences overlap, the **more specific** occurrence wins —
     * the one with the longer matched original span. Ties (equal span length) are broken first by
     * the lower original edit spec index, then by the earlier start index, for full determinism.
     * The lower-priority overlapping occurrence is rejected (kept in the result summary) rather than
     * silently dropped, and the higher-priority occurrence is applied.
     *
     * @param text Original file content (unmodified).
     * @param edits Caller-supplied edits in their original order.
     * @return A [PlanResult.Failure] if any edit spec cannot be matched, otherwise a
     *   [PlanResult.Success] carrying the accepted and rejected occurrences.
     */
    private fun planAndResolve(text: String, edits: List<EditSpec>): PlanResult {
        // Flatten all occurrences from every edit spec into a single global list.
        val planned = mutableListOf<PlannedEditOccurrence>()
        for ((index, edit) in edits.withIndex()) {
            val ranges = findAllRanges(text, edit.oldText)
            if (ranges.isEmpty()) {
                return PlanResult.Failure(
                    "Edit at index $index: 'oldText' not found (whitespace-insensitive match)"
                )
            }
            for (range in ranges) {
                planned.add(
                    PlannedEditOccurrence(
                        index = index,
                        oldText = edit.oldText,
                        newText = edit.newText,
                        range = range,
                        matchedSpanLength = range.endIndexExclusive - range.startIndex
                    )
                )
            }
        }

        // Resolve conflicts by priority: longer matched span first, then lower edit index, then
        // earlier start index for full determinism.
        val byPriority = planned.sortedWith(
            compareByDescending<PlannedEditOccurrence> { it.matchedSpanLength }
                .thenBy { it.index }
                .thenBy { it.range.startIndex }
        )
        val accepted = mutableListOf<PlannedEditOccurrence>()
        val rejected = mutableListOf<RejectedEdit>()
        for (candidate in byPriority) {
            val conflict = accepted.firstOrNull { overlaps(it.range, candidate.range) }
            if (conflict != null) {
                // Lower-priority overlapping occurrence is rejected; the higher-priority one is kept.
                // The reason names the kept conflicting occurrence by its edit spec index and
                // original-space range so the report is unambiguous.
                rejected.add(
                    RejectedEdit(
                        index = candidate.index,
                        reason = "Overlaps occurrence from edit spec ${conflict.index} " +
                            "at original range [${conflict.range.startIndex}, ${conflict.range.endIndexExclusive}) " +
                            "(kept higher-priority occurrence)"
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
     * Because all [PlannedEditOccurrence] ranges are disjoint (overlaps were rejected) and computed against
     * the original [text], applying from the highest start index downward keeps every earlier range
     * valid — no offset shifting occurs between edits.
     *
     * @param text Original file content.
     * @param accepted Edits accepted by [planAndResolve], in any order.
     * @return The modified text with all accepted edits applied.
     */
    private fun applyAccepted(text: String, accepted: List<PlannedEditOccurrence>): String {
        val ordered = accepted.sortedByDescending { it.range.startIndex }
        var result = text
        for (edit in ordered) {
            val r = edit.range
            result = result.substring(0, r.startIndex) + edit.newText + result.substring(r.endIndexExclusive)
        }
        return result
    }

    /**
     * Renders a human-readable report: a summary of requested edit specs and matched/applied/rejected
     * occurrences, followed by a unified diff of the applied changes.
     *
     * The summary distinguishes **requested edit specs** (caller-supplied edits) from
     * **occurrences** (individual matches in the original text). One edit spec may produce several
     * matched/applied/rejected occurrences.
     *
     * @param original Original file content.
     * @param modified File content after accepted occurrences were applied.
     * @param edits All caller-supplied edit specs (for the requested count).
     * @param plan The resolved plan (accepted + rejected occurrences).
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
        sb.append("- requested edit specs: ").append(edits.size).append('\n')
        sb.append("- matched occurrences: ").append(matched).append('\n')
        sb.append("- applied occurrences: ").append(plan.accepted.size).append('\n')
        sb.append("- rejected occurrences: ").append(plan.rejected.size).append('\n')
        if (plan.rejected.isNotEmpty()) {
            sb.append("Rejected occurrences (overlapping, lower priority):\n")
            for (r in plan.rejected) {
                sb.append("  - edit spec index ").append(r.index).append(": ").append(r.reason).append('\n')
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
     * Finds **all** non-overlapping occurrences of [needle] in [haystack] using a
     * whitespace-insensitive but **original-space** comparison.
     *
     * Matching is tolerant to *incidental* whitespace divergence between the caller-supplied
     * [needle] and the bytes on disk (e.g. `cat   walks` vs `cat walks`, tabs vs spaces):
     * whenever both cursors sit on whitespace, the comparison advances past each side's whole
     * whitespace run, so runs of differing length/kind compare equal. All other characters
     * must match exactly.
     *
     * Crucially the search never leaves original space: a match is reported as the *actual*
     * original `[start, end)` range it covers. Because the range includes the leading
     * indentation of the first matched line by construction, applying [newText] replaces that
     * indentation wholesale (no doubling), and there is no normalize-then-remap step that
     * could misplace the range.
     *
     * Occurrences are returned in source order and never overlap: once a match is consumed, the
     * next search resumes after the end of that match's original span, so self-overlapping
     * matches are skipped.
     *
     * @return The list of [MatchRange]s within the **original** [haystack] for every matched
     *   region, in source order. Empty if [needle] is not found.
     */
        private fun findAllRanges(haystack: String, needle: String): List<MatchRange> {
        if (needle.isEmpty()) return listOf(MatchRange(0, 0))
        // A whitespace-leading needle must anchor at a line start (string start or right after a
        // newline). This prevents it from absorbing a *preceding* line's trailing whitespace /
        // newline: e.g. matching `    fun()` against `class A {\n    fun()` must start at the
        // indentation run after the newline, not at the space following `{`.
        // A non-whitespace-leading needle is tried at every position (index-of semantics), which
        // also preserves intra-token repeats such as `aa` inside `aaaa`.
        val needleStartsWithWs = needle[0].isWhitespace()

        val ranges = mutableListOf<MatchRange>()
        var hi = 0
        while (hi < haystack.length) {
            val canStart = if (needleStartsWithWs) {
                hi == 0 || haystack[hi - 1] == '\n'
            } else {
                true
            }
            if (canStart) {
                val end = matchEndAt(haystack, hi, needle)
                if (end != null) {
                    ranges.add(MatchRange(hi, end))
                    // Resume after the consumed original span so self-overlapping matches are skipped.
                    hi = end
                    continue
                }
            }
            // No match starts here. Skip efficiently: a non-whitespace needle cannot start
            // inside a whitespace run, so jump to the run's end; otherwise step by one character
            // (this also keeps visiting line starts inside a whitespace run for ws-leading needles).
            hi = if (haystack[hi].isWhitespace() && !needleStartsWithWs) {
                endOfWhitespaceRun(haystack, hi)
            } else {
                hi + 1
            }
        }
        return ranges
    }

    /**
     * Attempts a whitespace-insensitive match of [needle] against [haystack] starting at
     * [hStart].
     *
     * Returns the original-space end index (exclusive) of the matched region if [needle]
     * matches, or `null` if it does not. See [findAllRanges] for the whitespace-run equality
     * rule. Trailing whitespace in [needle] is tolerated (a match may end inside a whitespace
     * run), while a match that runs past the end of [haystack] fails.
     */
    private fun matchEndAt(haystack: String, hStart: Int, needle: String): Int? {
        var hi = hStart
        var ni = 0
        while (ni < needle.length) {
            if (hi >= haystack.length) return null
            val hc = haystack[hi]
            val nc = needle[ni]
            if (hc.isWhitespace() && nc.isWhitespace()) {
                // Both sides on whitespace: advance each past its whole run; the runs compare
                // equal regardless of length or kind (spaces vs tabs vs newlines).
                hi = endOfWhitespaceRun(haystack, hi)
                ni = endOfWhitespaceRun(needle, ni)
            } else if (hc.isWhitespace() || nc.isWhitespace()) {
                // One side whitespace, the other not -> mismatch.
                return null
            } else if (hc != nc) {
                return null
            } else {
                hi++
                ni++
            }
        }
        // Needle fully consumed. The match ends at the current haystack cursor, which sits at
        // the first character after the matched region (possibly inside a trailing whitespace
        // run, which is deliberately included so it is replaced by newText).
        return hi
    }

    /**
     * Returns the index just past the whitespace run starting at [index] in [text].
     *
     * If [text] has no whitespace at [index] the returned value equals [index], so callers
     * that only invoke this when `text[index].isWhitespace()` still behave correctly.
     */
    private fun endOfWhitespaceRun(text: String, index: Int): Int {
        var i = index
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }
}
