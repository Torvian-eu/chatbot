package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.LineDiff
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.TextEditSpec
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseEditSpecs
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.instruction.UpdateInstructionError
import kotlinx.serialization.json.JsonObject

/**
 * `edit_instruction` server built-in tool.
 *
 * Applies `oldText` -> `newText` replacements to one instruction's message without rewriting the
 * whole row. Semantics mirror the worker `edit_file` tool: each spec replaces **all** of its
 * non-overlapping occurrences, every spec is matched against the **original** message (so caller
 * order is not sequential), overlapping matches are resolved deterministically (longer matched span
 * wins; ties by edit index, then earlier start), and the call fails when an `oldText` matches
 * nothing.
 *
 * The tool is message-only: the request it writes is built from the row it read, so `type`, `name`
 * and `custom` travel through unchanged and the model cannot retype or relabel a row that other
 * roles link. A kind whose text the server generates (`spawnable_agents`) stores no message and is
 * refused explicitly, instead of failing later with a validation error that describes the input
 * rather than the addressed row.
 *
 * The result is a human-readable report (never JSON): the operation header, the note that the change
 * reaches every linked role with those role ids, the edit summary, the rejected overlaps, and a
 * unified diff of the message. Reading the full text again is `read_instruction`'s job, so the
 * report is capped and says so when it truncates.
 *
 * @property instructionService User-scoped service providing the ownership-checked row and the
 *            content write.
 */
class EditInstructionTool(
    private val instructionService: InstructionService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.EDIT_INSTRUCTION_NAME

    /** Catalog spec for this tool: the single source of [name], [description], and [inputSchema]. */
    private val spec: ServerBuiltInToolCatalog.ServerBuiltInToolSpec =
        requireNotNull(ServerBuiltInToolCatalog.specFor(name)) {
            "Catalog must contain a spec for server built-in tool '$name'"
        }

    override val description: String get() = spec.description
    override val inputSchema: JsonObject get() = spec.inputSchema

    override suspend fun execute(
        input: JsonObject,
        context: ToolCallExecutionContext
    ): Either<ServerBuiltInToolHandlerError, String> = either {
        val validationErrors = mutableListOf<String>()
        addUnknownParameterErrors(
            input,
            setOf(
                ServerBuiltInToolCatalog.INSTRUCTION_ID_PROPERTY,
                ServerBuiltInToolCatalog.EDITS_PROPERTY
            ),
            validationErrors
        )
        val instructionId =
            parseRequiredLong(input, ServerBuiltInToolCatalog.INSTRUCTION_ID_PROPERTY, validationErrors)
        val edits = parseEditSpecs(input, ServerBuiltInToolCatalog.EDITS_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // instructionId and edits are non-null here: a null result always coincides with a recorded
        // validation error, and we bail out above when any error was recorded.
        val persisted = instructionService.getInstructionById(context.userId, instructionId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Instruction $instructionId not found or not accessible by the current user."
                )
            }
            .bind()

        // A row of this kind stores no message (its text is generated per linked role at read time),
        // so there is nothing to replace and no way to persist the result.
        ensure(persisted.type != AgentInstructionTypes.SPAWNABLE_AGENTS) {
            ServerBuiltInToolHandlerError.OperationFailed(
                "instruction_message_not_editable",
                "Instruction ${persisted.id} is a '${AgentInstructionTypes.SPAWNABLE_AGENTS}' " +
                    "instruction: its text is generated for each linked agent role at read time, so " +
                    "it stores no message to edit."
            )
        }

        val outcome = applyEdits(persisted.message, edits!!).bind()

        // Re-sending the row's own kind, label and kind-specific data is what makes this tool
        // message-only: nothing but the text can change, and a `model_specific` row keeps its model
        // target (already parsed back from storage by the read above).
        val request = UpdateInstructionRequest(
            id = persisted.id,
            type = persisted.type,
            name = persisted.name,
            message = outcome.newMessage,
            custom = persisted.custom
        )
        instructionService.updateInstruction(context.userId, request)
            .mapLeft { error -> error.toHandlerError(persisted.id) }
            .bind()
        renderReport(persisted, outcome, edits)
    }

    /**
     * Applies the caller-supplied edit batch to the stored message.
     *
     * Planning never mutates the source: every spec is matched independently against the original
     * message, all of its non-overlapping occurrences are collected, and the caller order is
     * therefore irrelevant to which ranges are found. A spec with zero occurrences fails the whole
     * call naming its index, so a stale or hallucinated text cannot leave a partially edited row.
     * Overlapping occurrences are resolved deterministically (longer matched span, then lower edit
     * index, then earlier start); the losing occurrence is dropped and recorded for the report.
     * Accepted occurrences are applied in reverse start order so earlier replacements never shift
     * later ranges.
     *
     * @param message The stored message, unchanged by this function.
     * @param edits The parsed edit specs in caller order.
     * @return Either an `old_text_not_found` operation failure or the [EditOutcome] carrying the
     *         edited message plus the occurrence statistics for the report.
     */
    private fun applyEdits(
        message: String,
        edits: List<TextEditSpec>
    ): Either<ServerBuiltInToolHandlerError, EditOutcome> = either {
        val occurrencesPerEdit = edits.mapIndexed { editIndex, edit ->
            findAllOccurrences(message, edit.oldText)
                .map { range -> Occurrence(editIndex, range.first, range.second) }
        }

        edits.indices.forEach { editIndex ->
            ensure(occurrencesPerEdit[editIndex].isNotEmpty()) {
                ServerBuiltInToolHandlerError.OperationFailed(
                    "old_text_not_found",
                    "Edit at index ${formatEditIndex(editIndex)}: 'oldText' not found in the " +
                        "instruction message"
                )
            }
        }

        val occurrences = occurrencesPerEdit.flatten()
        val resolution = resolveConflicts(occurrences)
        var edited = message
        resolution.accepted.sortedByDescending { it.start }.forEach { occurrence ->
            edited = edited.replaceRange(
                occurrence.start,
                occurrence.endExclusive,
                edits[occurrence.editIndex].newText
            )
        }
        EditOutcome(
            newMessage = edited,
            matchedOccurrences = occurrences.size,
            appliedOccurrences = resolution.accepted.size,
            rejected = resolution.rejected
        )
    }

    /**
     * Finds every non-overlapping occurrence of [needle] in [text].
     *
     * Matching is exact and non-overlapping: after a match at `start`, the next search begins just
     * past its end, so adjacent-but-disjoint occurrences are all reported and self-overlapping
     * matches (e.g. `aa` inside `aaa`) are skipped.
     *
     * @param text The text to scan.
     * @param needle The exact substring to locate (never blank; validated during parsing).
     * @return The list of `[start, endExclusive)` ranges in ascending start order.
     */
    private fun findAllOccurrences(text: String, needle: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = text.indexOf(needle)
        while (start >= 0) {
            result.add(start to start + needle.length)
            start = text.indexOf(needle, startIndex = start + needle.length)
        }
        return result
    }

    /**
     * Resolves overlapping occurrences inside the message deterministically.
     *
     * The highest-priority occurrence (longest matched span; ties broken by lower edit index, then
     * earlier start) is accepted greedily, and every later candidate that overlaps an accepted one
     * is rejected. This mirrors the worker `edit_file` conflict policy, so the outcome does not
     * depend on caller-supplied edit order. Rejected occurrences carry a human-readable reason
     * naming the kept conflicting occurrence, so the report is unambiguous.
     *
     * @param occurrences All matched occurrence ranges of the message.
     * @return The disjoint accepted occurrences plus the rejected ones with their reasons.
     */
    private fun resolveConflicts(occurrences: List<Occurrence>): ConflictResolution {
        val byPriority = occurrences.sortedWith(
            compareByDescending<Occurrence> { it.endExclusive - it.start }
                .thenBy { it.editIndex }
                .thenBy { it.start }
        )
        val accepted = mutableListOf<Occurrence>()
        val rejected = mutableListOf<Pair<Occurrence, String>>()
        for (candidate in byPriority) {
            val conflict = accepted.firstOrNull {
                it.start < candidate.endExclusive && candidate.start < it.endExclusive
            }
            if (conflict != null) {
                rejected.add(
                    candidate to "Overlaps occurrence from edit spec index " +
                        "${formatEditIndex(conflict.editIndex)} at range [${conflict.start}, " +
                        "${conflict.endExclusive}) (kept higher-priority occurrence)"
                )
            } else {
                accepted.add(candidate)
            }
        }
        return ConflictResolution(accepted, rejected)
    }

    /**
     * Renders the human-readable edit report: the operation header, the propagation note naming the
     * linked roles, the edit summary (mirroring the worker `edit_file` report), the rejected-overlap
     * details, and a unified diff of the message against the original.
     *
     * The report is plain text, never JSON: the caller needs to know what changed and who is
     * affected, not the whole row. The diff is rendered with [LineDiff.unifiedDiff] (Git-style `@@`
     * hunks with 3 context lines) and reads `(no changes)` when the batch only matched no-op
     * replacements. The complete report is capped at [MAX_REPORT_BYTES] UTF-8 bytes; when the cap is
     * hit, the diff body is truncated at a UTF-8 boundary and a notice points at `read_instruction`
     * for the full text.
     *
     * @param persisted The row as read before the write: it supplies the operation header (name, id,
     *            kind) and the original message the diff is computed against.
     * @param outcome The planned outcome (edited message plus occurrence statistics).
     * @param edits The caller-supplied edit specs (for the requested count).
     * @return The rendered report string.
     */
    private fun renderReport(
        persisted: AgentInstructionDto,
        outcome: EditOutcome,
        edits: List<TextEditSpec>,
    ): String {
        val summary = buildString {
            append("Edited instruction '").append(persisted.name)
                .append("' (id: ").append(persisted.id)
                .append(", type: ").append(persisted.type).append("):\n")
            append(formatPropagationNote(persisted.linkedRoleIds)).append('\n')
            append("Edit summary:\n")
            append("- requested edit specs: ").append(edits.size).append('\n')
            append("- matched occurrences: ").append(outcome.matchedOccurrences).append('\n')
            append("- applied occurrences: ").append(outcome.appliedOccurrences).append('\n')
            append("- rejected occurrences: ").append(outcome.rejected.size).append('\n')
        }
        val rejectedSection = buildString {
            if (outcome.rejected.isNotEmpty()) {
                append("Rejected occurrences (overlapping, lower priority):\n")
                for ((occurrence, reason) in outcome.rejected) {
                    append("  - edit spec index ").append(formatEditIndex(occurrence.editIndex))
                        .append(": ").append(reason).append('\n')
                }
            }
        }
        val diffBody = LineDiff.unifiedDiff(
            persisted.message.split('\n'),
            outcome.newMessage.split('\n'),
            contextLines = 3
        )
        val diffSection = diffBody.ifEmpty { "(no changes)\n" }
        val diffHeader = "--- diff ---\n"
        val completeReport = summary + rejectedSection + diffHeader + diffSection
        if (utf8ByteCount(completeReport) <= MAX_REPORT_BYTES) return completeReport

        val notice = "\n[Output truncated at $MAX_REPORT_BYTES bytes. " +
            "Use read_instruction to read the full instruction text.]\n"
        val bodyBudget = MAX_REPORT_BYTES -
            utf8ByteCount(summary) - utf8ByteCount(rejectedSection) - utf8ByteCount(diffHeader) - utf8ByteCount(notice)
        val (truncatedBody, _) = truncateBytes(diffSection, bodyBudget)
        return summary + rejectedSection + diffHeader + truncatedBody + notice
    }

    /**
     * Counts the bytes in [text]'s UTF-8 representation for the report size contract.
     *
     * @param text Text to measure.
     * @return Number of UTF-8 bytes used by [text].
     */
    private fun utf8ByteCount(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * Truncates [text] to at most [maxBytes] UTF-8 bytes without splitting a multi-byte sequence.
     *
     * @param text Text to truncate.
     * @param maxBytes Byte budget; a non-positive budget yields an empty string.
     * @return The truncated text and `true` when truncation occurred (text exceeded the budget).
     */
    private fun truncateBytes(text: String, maxBytes: Int): Pair<String, Boolean> {
        if (maxBytes <= 0) return "" to true
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text to false
        var end = maxBytes
        // Back up while the byte at `end` is a UTF-8 continuation byte (0b10xxxxxx), so the cut
        // never lands inside a multi-byte character.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return bytes.copyOfRange(0, end).toString(Charsets.UTF_8) to true
    }

    /**
     * A single matched occurrence of an edit spec inside the message.
     *
     * @property editIndex Index of the edit spec in the caller-supplied batch (for tie-breaking).
     * @property start Start offset (inclusive) in the message.
     * @property endExclusive End offset (exclusive) in the message.
     */
    private data class Occurrence(
        val editIndex: Int,
        val start: Int,
        val endExclusive: Int
    )

    /**
     * Result of [resolveConflicts] for the message.
     *
     * @property accepted Disjoint occurrences to apply (in no particular order).
     * @property rejected Overlapping occurrences dropped during resolution, with reasons.
     */
    private data class ConflictResolution(
        val accepted: List<Occurrence>,
        val rejected: List<Pair<Occurrence, String>>
    )

    /**
     * Outcome of [applyEdits] on the message.
     *
     * @property newMessage The edited message.
     * @property matchedOccurrences Total occurrences of all edit specs found in the message.
     * @property appliedOccurrences Total occurrences actually applied after conflict resolution.
     * @property rejected The occurrences dropped during conflict resolution, for the report.
     */
    private data class EditOutcome(
        val newMessage: String,
        val matchedOccurrences: Int,
        val appliedOccurrences: Int,
        val rejected: List<Pair<Occurrence, String>>
    )

    private companion object {
        /** Maximum UTF-8 byte size of a successful edit report. */
        const val MAX_REPORT_BYTES = 5_000
    }
}

/**
 * States who the content change reaches, naming the linked roles (or that none link the row).
 *
 * The wording distinguishes one linked role from several because the derived "shared" state is
 * `linkedRoleIds.size > 1`: claiming that a row only one role links is shared would contradict what
 * every other surface reports about the same row.
 *
 * @param linkedRoleIds Ids of the agent roles that link the edited row.
 * @return The propagation line of the edit report.
 */
private fun formatPropagationNote(linkedRoleIds: Set<Long>): String = when {
    linkedRoleIds.isEmpty() -> "No agent role links this instruction yet."
    linkedRoleIds.size == 1 ->
        "The change applies to every linked agent role (ids: ${linkedRoleIds.sorted().joinToString(", ")})."
    else ->
        "This instruction is shared: the change applies to every linked agent role " +
            "(ids: ${linkedRoleIds.sorted().joinToString(", ")})."
}

/**
 * Formats an edit-array index for inclusion in a diagnostic message.
 *
 * Positive indices receive an explicit qualifier because the value refers to a zero-based JSON-array
 * position; index zero remains concise for the first edit.
 *
 * @param index Zero-based position of the edit in the caller-supplied array.
 * @return The index text, with `(0-based)` appended when [index] is greater than zero.
 */
private fun formatEditIndex(index: Int): String =
    index.toString() + if (index > 0) " (0-based)" else ""

/**
 * Maps an [UpdateInstructionError] to an LLM-readable [ServerBuiltInToolHandlerError].
 *
 * Not-found/not-accessible collapses into one message (no existence leak), and the service's
 * validation reason is forwarded verbatim because it states the violated rule. A malformed stored
 * `custom` value reaches this arm, which is why the message is passed through rather than replaced.
 *
 * @receiver The typed update-instruction failure.
 * @param instructionId The addressed row, used by the not-found message.
 * @return The corresponding handler error.
 */
private fun UpdateInstructionError.toHandlerError(instructionId: Long): ServerBuiltInToolHandlerError =
    when (this) {
        is UpdateInstructionError.NotFound ->
            ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                "Instruction $instructionId not found or not accessible by the current user."
            )

        is UpdateInstructionError.ValidationFailed ->
            ServerBuiltInToolHandlerError.OperationFailed("instruction_validation_failed", reason)

        is UpdateInstructionError.LinkedRoleInstructionListInvalid ->
            // Unreachable from this tool (it re-sends the row's own kind), kept for exhaustiveness.
            ServerBuiltInToolHandlerError.OperationFailed(
                "linked_role_instruction_list_invalid",
                "Instruction $instructionId cannot change: agent role(s) " +
                    "${linkedRoleIds.joinToString()} would be left with an invalid instruction list " +
                    "($reason)."
            )
    }
