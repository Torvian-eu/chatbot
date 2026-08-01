package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.*
import eu.torvian.chatbot.worker.builtin.validation.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Performs selective edits inside a text file using `oldText` -> `newText` replacements.
 *
 * Behavior:
 * - Matching is **exact**: every character in the caller-supplied `oldText` must match the
 *   corresponding character in the file, including whitespace. Spaces, tabs, and their
 *   specific count must all be identical. However, **end-of-line sequences are normalized**:
 *   the file is read and converted to Unix-style `\n` before matching, and the caller's
 *   `oldText`/`newText` are similarly normalized, so callers can always use `\n` regardless
 *   of the file's actual line-ending style (CRLF, LF, or CR). On write, the file's original
 *   EOL style is restored.
 * - Each caller-supplied edit spec matches **all** of its non-overlapping occurrences in the
 *   normalized text (not just the first). One edit spec may therefore produce multiple planned
 *   occurrences.
 * - Edits are planned as a single global batch against the *same* normalized text. The planning
 *   phase never mutates the source, so caller-supplied order does not influence which ranges
 *   are found.
 * - Conflicts are resolved deterministically across **all** occurrences: when two planned
 *   occurrences overlap, the **more specific** occurrence wins (longer matched original span
 *   first; ties broken by lower original edit index, then by earlier start index). The
 *   lower-priority overlapping occurrence is rejected with a clear summary rather than silently
 *   dropped.
 * - Accepted occurrences are applied in **reverse start-index order** against the normalized
 *   text, which avoids offset shifting and preserves the planned ranges.
 * - `dryRun` produces a Git-compatible unified diff plus a summary (requested edit specs /
 *   matched / applied / rejected occurrences) without modifying the file.
 * - If an edit spec matches zero occurrences, the whole operation fails naming that edit spec's
 *   index.
 */
class EditFileTool : BuiltInTool {
    override val name: String = "edit_file"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    /**
     * Applies the caller-supplied `oldText` -> `newText` edits to the referenced file.
     *
     * Line endings in the file are normalized to `\n` before matching, and the caller's
     * `oldText`/`newText` are similarly normalized, so callers may always use `\n` as the
     * EOL marker regardless of the file's actual encoding (CRLF, LF, or CR). The file's
     * original EOL style is detected and restored on write, so the file format is preserved.
     *
     * Edits are matched exactly in normalized space, planned as a single conflict-resolved
     * batch, then applied. Validation, workspace containment, and read/write failures are
     * reported as [BuiltInToolExecutionResult.isError] rather than thrown.
     *
     * @param input Tool input: `path` (string), `edits` (array of `oldText`/`newText`
     *   objects), and an optional `dryRun` flag.
     * @param context Execution context supplying the workspace root and IO dispatcher.
     * @return A [BuiltInToolExecutionResult]: the diff/report on success, or an error
     *   result carrying [BuiltInToolExecutionError] details on failure.
     */
    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("path", "edits", "dryRun")
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val path = parseRequiredString(input, "path", validationErrors)
        val dryRun = parseOptionalBoolean(input, "dryRun", defaultValue = false, validationErrors)

        val editsJson = input["edits"]
        val edits = when (editsJson) {
            null -> {
                validationErrors.add("Missing required argument: edits")
                emptyList()
            }

            !is JsonArray -> {
                validationErrors.add("Argument 'edits' must be an array")
                emptyList()
            }

            else -> {
                editsJson.mapIndexed { index, element ->
                    val obj = element as? JsonObject
                    if (obj == null) {
                        validationErrors.add("Edit at index ${formatEditIndex(index)} is not an object")
                        return@mapIndexed null
                    }

                    val oldRaw = obj["oldText"]
                    val oldText = when {
                        oldRaw == null -> {
                            validationErrors.add("Edit at index ${formatEditIndex(index)} missing 'oldText'")
                            null
                        }

                        oldRaw !is JsonPrimitive || !oldRaw.isString -> {
                            validationErrors.add("Edit at index ${formatEditIndex(index)}: 'oldText' must be a string")
                            null
                        }

                        oldRaw.content.isBlank() -> {
                            validationErrors.add("Edit at index ${formatEditIndex(index)} has empty or whitespace-only 'oldText'")
                            null
                        }

                        else -> normalizeEol(oldRaw.content)
                    }
                    if (oldText == null) return@mapIndexed null

                    val newRaw = obj["newText"]
                    val newText = when {
                        newRaw == null -> {
                            validationErrors.add("Edit at index ${formatEditIndex(index)} missing 'newText'")
                            null
                        }

                        newRaw !is JsonPrimitive || !newRaw.isString -> {
                            validationErrors.add("Edit at index ${formatEditIndex(index)}: 'newText' must be a string")
                            null
                        }

                        else -> normalizeEol(newRaw.content)
                    }
                    if (newText == null) return@mapIndexed null

                    EditSpec(oldText, newText)
                }.filterNotNull()
            }
        }

        if (edits.isEmpty() && editsJson is JsonArray && editsJson.isEmpty()) {
            validationErrors.add("At least one edit is required")
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
            val fileContent = try {
                Files.readString(target, Charsets.UTF_8)
            } catch (_: NoSuchFileException) {
                return@withContext builtInToolErrorResult(BuiltInToolExecutionError.NOT_FOUND, "File not found: $path")
            } catch (e: Exception) {
                return@withContext builtInToolErrorResult(
                    BuiltInToolExecutionError.EXECUTION_FAILED,
                    "Failed to read file: ${e.message}"
                )
            }

            // Detect the file's original EOL style and normalize the content to Unix `\n`.
            // All matching and application happens in \n-normalized space. On write, the
            // content is denormalized back to the original EOL style so the file format is
            // preserved.
            val eol = detectEol(fileContent)
            val normalizedOriginal = normalizeEol(fileContent)

            // Plan + resolve conflicts against the normalized original text (no mutation yet).
            val plan = planAndResolve(normalizedOriginal, edits)
            if (plan is PlanResult.Failure) {
                return@withContext builtInToolErrorResult(BuiltInToolExecutionError.EXECUTION_FAILED, plan.message)
            }
            val success = plan as PlanResult.Success

            // Apply accepted edits in reverse start-index order against the normalized text.
            val normalizedModified = applyAccepted(normalizedOriginal, success.accepted)

            // Denormalize the result back to the file's original EOL style before writing.
            val modified = denormalizeEol(normalizedModified, eol)

            val report = renderReport(normalizedOriginal, normalizedModified, edits, success)
            if (dryRun) {
                BuiltInToolExecutionResult(output = report)
            } else {
                try {
                    Files.writeString(target, modified, Charsets.UTF_8)
                    BuiltInToolExecutionResult(output = report)
                } catch (e: Exception) {
                    return@withContext builtInToolErrorResult(
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
     * @property oldText Text to locate (matched exactly, already EOL-normalized).
     * @property newText Replacement text (already EOL-normalized).
     */
    private data class EditSpec(val oldText: String, val newText: String)

    /**
     * Range of a match in the normalized text, covering start (inclusive) to end (exclusive).
     *
     * @property startIndex Start offset (inclusive) in the normalized string.
     * @property endIndexExclusive End offset (exclusive) in the normalized string.
     */
    private data class MatchRange(val startIndex: Int, val endIndexExclusive: Int)

    /**
     * A single planned occurrence of a caller-supplied edit spec after its match has been located
     * in the normalized text.
     *
     * One [EditSpec] may produce several [PlannedEditOccurrence]s (one per non-overlapping match in
     * the normalized text). The [range] is expressed in normalized-space coordinates and
     * [matchedSpanLength] is the length of that normalized span. Both are used for conflict
     * resolution and for applying the occurrence.
     *
     * @property index Original caller-supplied edit spec index (for deterministic tie-breaking and reporting).
     * @property oldText Text that was matched.
     * @property newText Replacement text.
     * @property range Normalized-space range of the matched region.
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
     * An occurrence that matched the normalized text but was rejected during conflict resolution.
     *
     * @property index Original caller-supplied edit spec index of the rejected occurrence.
     * @property reason Human-readable explanation identifying the kept conflicting occurrence
     *   (its edit spec index and normalized-space range).
     */
    private data class RejectedEdit(val index: Int, val reason: String)

    /**
     * Outcome of [planAndResolve]: either a planning failure or the resolved plan.
     */
    private sealed interface PlanResult {
        /**
         * Planning could not proceed because an edit spec matched zero occurrences.
         *
         * @property message Explains which edit spec index failed and why.
         */
        data class Failure(val message: String) : PlanResult

        /**
         * Planning succeeded with conflict resolution applied.
         *
         * @property accepted Occurrences chosen to be applied (disjoint ranges).
         * @property rejected Lower-priority occurrences dropped due to overlap, kept for reporting.
         */
        data class Success(val accepted: List<PlannedEditOccurrence>, val rejected: List<RejectedEdit>) : PlanResult
    }

    /**
     * Plans every occurrence of every edit spec against the same [text] and resolves overlapping
     * matches deterministically across all occurrences.
     *
     * Planning never mutates [text]: each edit spec is matched independently against the normalized
     * string, and **all** of its non-overlapping occurrences are collected, so caller order cannot
     * change which ranges are found. If any edit spec produces zero matches, the whole operation
     * fails naming that edit spec's index.
     *
     * Overlap policy: when two planned occurrences overlap, the **more specific** occurrence wins —
     * the one with the longer matched normalized span. Ties (equal span length) are broken first by
     * the lower original edit index, then by the earlier start index, for full determinism. The
     * lower-priority overlapping occurrence is rejected (kept in the result summary) rather than
     * silently dropped, and the higher-priority occurrence is applied.
     *
     * @param text Normalized file content (unmodified, all `\n` line endings).
     * @param edits Caller-supplied edits in their original order (already EOL-normalized).
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
                    "Edit at index ${formatEditIndex(index)}: 'oldText' not found (exact match after EOL normalization)"
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
                // normalized-space range so the report is unambiguous.
                rejected.add(
                    RejectedEdit(
                        index = candidate.index,
                        reason = "Overlaps occurrence from edit spec index ${formatEditIndex(conflict.index)} " +
                                "at normalized range [${conflict.range.startIndex}, ${conflict.range.endIndexExclusive}) " +
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
     * Because all [PlannedEditOccurrence] ranges are disjoint (overlaps were rejected) and computed
     * against the normalized [text], applying from the highest start index downward keeps every
     * earlier range valid — no offset shifting occurs between edits.
     *
     * @param text Normalized file content (all `\n` line endings).
     * @param accepted Edits accepted by [planAndResolve], in any order.
     * @return The modified text with all accepted edits applied, still in normalized form.
     */
    private fun applyAccepted(text: String, accepted: List<PlannedEditOccurrence>): String {
        val ordered = accepted.sortedByDescending { it.range.startIndex }
        var result = text
        for ((_, _, newText, r) in ordered) {
            result = result.substring(0, r.startIndex) + newText + result.substring(r.endIndexExclusive)
        }
        return result
    }

    /**
     * Renders a human-readable report: a summary of requested edit specs and matched/applied/rejected
     * occurrences, followed by a Git-compatible unified diff of the applied changes.
     *
     * The summary distinguishes **requested edit specs** (caller-supplied edits) from
     * **occurrences** (individual matches in the normalized text). One edit spec may produce several
     * matched/applied/rejected occurrences.
     *
     * The diff is produced by [LineDiff.unifiedDiff], which aligns the two files via a
     * longest-common-subsequence edit script and emits `@@ -a,b +c,d @@` hunks with surrounding
     * context lines. Unlike a positional line-by-line comparison, this keeps the diff compact and
     * correct even when an edit near the top of the file shifts the alignment of every later line.
     *
     * Both [original] and [modified] are in normalized form (all `\n`), so the diff is also
     * normalized. This is intentional: the caller thinks in Unix-style newlines, and showing raw
     * CRLF in the diff output would be confusing.
     *
     * @param original Normalized original file content (all `\n`).
     * @param modified Normalized file content after accepted occurrences were applied.
     * @param edits All caller-supplied edit specs (for the requested count).
     * @param plan The resolved plan (accepted + rejected occurrences).
     * @return The rendered report string (summary followed by a unified diff).
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
            for ((index, reason) in plan.rejected) {
                sb.append("  - edit spec index ").append(formatEditIndex(index)).append(": ").append(reason).append('\n')
            }
        }
        sb.append("--- diff ---\n")
        // Split on '\n' so each list entry is a line *without* its terminator; a trailing empty
        // string preserves a missing final newline and is reported faithfully rather than invented.
        val originalLines = original.split('\n')
        val modifiedLines = modified.split('\n')
        val diff = LineDiff.unifiedDiff(originalLines, modifiedLines, contextLines = 3)
        if (diff.isEmpty()) {
            sb.append("(no changes)\n")
        } else {
            sb.append(diff)
        }
        return sb.toString()
    }

    /**
     * Returns `true` when two normalized-space ranges overlap (share at least one character).
     *
     * Two half-open ranges `[aStart, aEnd)` and `[bStart, bEnd)` overlap iff
     * `aStart < bEnd && bStart < aEnd`.
     *
     * @param a First range.
     * @param b Second range.
     * @return `true` if [a] and [b] share at least one character, `false` otherwise.
     */
    private fun overlaps(a: MatchRange, b: MatchRange): Boolean =
        a.startIndex < b.endIndexExclusive && b.startIndex < a.endIndexExclusive

    /**
     * Finds **all** non-overlapping occurrences of [needle] in [haystack] using
     * **exact** character-by-character matching.
     *
     * Every character in [needle] must match the corresponding character in [haystack],
     * including whitespace (spaces, tabs, newlines and their exact count). No whitespace
     * normalization or anchoring is performed beyond the initial EOL normalization already
     * applied to both [haystack] and [needle] — the needle is searched at every position.
     *
     * Occurrences are returned in source order and never overlap: once a match is consumed, the
     * next search resumes after the end of that match, so self-overlapping matches are skipped.
     *
     * @param haystack Normalized file content searched for matches (all `\n`).
     * @param needle Caller-supplied text to locate (matched exactly, already EOL-normalized).
     * @return The list of [MatchRange]s within the **normalized** [haystack] for every matched
     *   region, in source order. Empty if [needle] is not found.
     */
    private fun findAllRanges(haystack: String, needle: String): List<MatchRange> {
        if (needle.isEmpty()) return emptyList()
        val ranges = mutableListOf<MatchRange>()
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) break
            ranges.add(MatchRange(idx, idx + needle.length))
            from = idx + needle.length
        }
        return ranges
    }

    /**
     * Formats an edit-array index for inclusion in a diagnostic message.
     *
     * Positive indices receive an explicit qualifier because the value refers to a zero-based
     * JSON-array position; index zero remains concise for the first edit.
     *
     * @param index Zero-based position of the edit in the caller-supplied array.
     * @return The index text, with `(zero-based)` appended when [index] is greater than zero.
     */
    private fun formatEditIndex(index: Int): String =
        index.toString() + if (index > 0) " (zero-based)" else ""

    // -----------------------------------------------------------------------------------------
    // EOL (end-of-line) normalization helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Identifies the dominant line-ending style in a text file.
     *
     * Detection order (first match wins):
     * 1. If the text contains `\r\n` → [EolStyle.CRLF]
     * 2. If the text contains `\r` (not followed by `\n`) → [EolStyle.CR]
     * 3. Otherwise → [EolStyle.LF]
     *
     * Mixed files (e.g. a mix of CRLF and LF) are handled by preferring the dominant style via
     * the detection heuristic above. If a file contains no line-ending characters at all (e.g. a
     * single line with no newline), [EolStyle.LF] is returned as the default, since applying
     * LF-normalized content to such a file is a no-op anyway.
     *
     * @param content Raw file content (as read from disk).
     * @return The detected [EolStyle].
     */
    private fun detectEol(content: String): EolStyle {
        if (content.contains("\r\n")) return EolStyle.CRLF
        if (content.contains('\r')) return EolStyle.CR
        return EolStyle.LF
    }

    /**
     * Converts any line-ending style (CRLF, CR, LF) to Unix-style LF (`\n`).
     *
     * Normalization proceeds in two passes:
     * 1. Replace all `\r\n` sequences with `\n` (handles CRLF).
     * 2. Replace any remaining bare `\r` with `\n` (handles classic Mac CR).
     *
     * This is order-safe: a bare `\r` that was part of a `\r\n` pair is already consumed by
     * the first pass and will not be doubled.
     *
     * @param content Text with any line-ending style.
     * @return Text with all line endings converted to `\n`.
     */
    private fun normalizeEol(content: String): String =
        content.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * Converts normalized Unix-style LF (`\n`) back to the file's original EOL style.
     *
     * @param content Text with Unix `\n` line endings.
     * @param eol The target EOL style to restore.
     * @return Text with line endings converted to [eol].
     */
    private fun denormalizeEol(content: String, eol: EolStyle): String = when (eol) {
        EolStyle.LF -> content
        EolStyle.CRLF -> content.replace("\n", "\r\n")
        EolStyle.CR -> content.replace('\n', '\r')
    }

    /**
     * End-of-line style enum for a text file.
     */
    private enum class EolStyle {
        /** Windows-style: `\r\n` */
        CRLF,
        /** Unix-style: `\n` */
        LF,
        /** Classic Mac-style: `\r` */
        CR
    }
}