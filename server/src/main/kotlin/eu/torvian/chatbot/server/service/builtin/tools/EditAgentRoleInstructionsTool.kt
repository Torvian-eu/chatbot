package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.LineDiff
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.TextEditSpec
import eu.torvian.chatbot.server.service.builtin.addUnknownParameterErrors
import eu.torvian.chatbot.server.service.builtin.invalidInputError
import eu.torvian.chatbot.server.service.builtin.parseEditSpecs
import eu.torvian.chatbot.server.service.builtin.parseRequiredLong
import eu.torvian.chatbot.server.service.core.AgentRoleService
import kotlinx.serialization.json.JsonObject

/**
 * `edit_agent_role_instructions` server built-in tool.
 *
 * Applies `oldText` -> `newText` replacements to the instruction messages of a role without
 * rewriting the whole list. Semantics mirror the worker `edit_file` tool adapted to a flat list of
 * instruction texts: each edit spec replaces **all** of its non-overlapping occurrences across the
 * role's instruction messages, all edits are matched against the **original** messages (caller
 * order is not sequential), overlapping matches are resolved deterministically (longer matched span
 * wins; ties by edit index, then earlier start), and the operation fails when an `oldText` matches
 * nothing anywhere in the list. Only the `message` field is edited; `name` and `custom` are never
 * touched. The merged state is applied through the existing full-replacement role update, so every
 * other role field is preserved unchanged.
 *
 * Instead of echoing the full role JSON (a token waste), the tool returns a human-readable edit
 * report: an edit summary (requested/matched/applied/rejected occurrences, mirroring the worker
 * `edit_file` report), the rejected-overlap details, and a unified diff per changed instruction
 * (see [renderReport]). `read_agent_role` remains the tool that returns the full role.
 *
 * @property agentRoleService User-scoped role service used for the ownership-checked load and update.
 */
class EditAgentRoleInstructionsTool(
    private val agentRoleService: AgentRoleService
) : ServerBuiltInTool {

    override val name: String = ServerBuiltInToolCatalog.EDIT_AGENT_ROLE_INSTRUCTIONS_NAME

    /** Catalog spec for this tool: the single source of [name], [description], and [inputSchema]. */
    private val spec: ServerBuiltInToolCatalog.ServerBuiltInToolSpec =
        requireNotNull(ServerBuiltInToolCatalog.specFor(name)) {
            "Catalog must contain a spec for server built-in tool '$name'"
        }

    override val description: String get() = spec.description
    override val inputSchema: JsonObject get() = spec.inputSchema

    override suspend fun execute(
        userId: Long,
        input: JsonObject
    ): Either<ServerBuiltInToolHandlerError, String> = either {
        val validationErrors = mutableListOf<String>()
        addUnknownParameterErrors(
            input,
            setOf(
                ServerBuiltInToolCatalog.ROLE_ID_PROPERTY,
                ServerBuiltInToolCatalog.EDITS_PROPERTY
            ),
            validationErrors
        )
        val roleId = parseRequiredLong(input, ServerBuiltInToolCatalog.ROLE_ID_PROPERTY, validationErrors)
        val edits = parseEditSpecs(input, ServerBuiltInToolCatalog.EDITS_PROPERTY, validationErrors)
        if (validationErrors.isNotEmpty()) {
            raise(invalidInputError(validationErrors))
        }

        // roleId is non-null here: a null result always coincides with a recorded validation error,
        // and we bail out above when any error was recorded.
        val persisted = agentRoleService.getRoleById(userId, roleId!!)
            .mapLeft {
                ServerBuiltInToolHandlerError.NotFoundOrNotAccessible(
                    "Agent role $roleId not found or not accessible by the current user."
                )
            }
            .bind()

        val outcome = applyEdits(persisted.instructions, edits!!).bind()

        val request = UpdateAgentRoleRequest(
            name = persisted.name,
            displayName = persisted.displayName,
            description = persisted.description,
            modelId = persisted.modelId,
            modelSettingsId = persisted.modelSettingsId,
            toolIds = persisted.tools,
            spawnableAgentRoleIds = persisted.spawnableAgentRoleIds,
            instructions = outcome.newInstructions
        )

        agentRoleService.updateRole(userId, roleId, request)
            .mapLeft { error -> error.toHandlerError() }
            .bind()
        renderReport(persisted, outcome, edits)
    }

    /**
     * Applies the caller-supplied edit batch to the instruction messages.
     *
     * Planning never mutates the source: every edit spec is matched independently against each
     * instruction's original message, all of its non-overlapping occurrences are collected, and the
     * caller order is therefore irrelevant to which ranges are found. An edit spec with zero
     * occurrences anywhere in the list fails the whole operation naming the spec's index (mirroring
     * the worker `edit_file` behavior). Overlapping occurrences inside one message are resolved
     * deterministically: the longer matched span wins, then the lower edit index, then the earlier
     * start; the losing occurrence is dropped and recorded for the report. Accepted occurrences are
     * applied in reverse start order so earlier replacements never shift later ranges.
     *
     * @param instructions The persisted instruction DTOs (unchanged by this function).
     * @param edits The parsed edit specs in caller order.
     * @return Either an `old_text_not_found` operation failure or the [EditOutcome] carrying the
     *         edited list plus the occurrence statistics for the report.
     */
    private fun applyEdits(
        instructions: List<AgentInstructionDto>,
        edits: List<TextEditSpec>
    ): Either<ServerBuiltInToolHandlerError, EditOutcome> = either {
        // Occurrence ranges grouped per instruction message, per edit spec, in the original texts.
        val occurrencesPerMessage = instructions.map { instruction ->
            edits.mapIndexed { editIndex, edit ->
                findAllOccurrences(instruction.message, edit.oldText)
                    .map { range -> Occurrence(editIndex, range.first, range.second) }
            }
        }

        // A no-match edit is almost certainly a hallucinated or stale oldText; fail loudly naming
        // its index instead of silently applying a partial batch.
        edits.indices.forEach { editIndex ->
            val totalMatches = occurrencesPerMessage.sumOf { occurrences -> occurrences[editIndex].size }
            ensure(totalMatches != 0) {
                ServerBuiltInToolHandlerError.OperationFailed(
                    "old_text_not_found",
                    "Edit at index ${formatEditIndex(editIndex)}: 'oldText' not found in any instruction message"
                )
            }
        }

        val newInstructions = mutableListOf<AgentInstructionDto>()
        val rejected = mutableListOf<RejectedOccurrence>()
        var matchedOccurrences = 0
        var appliedOccurrences = 0
        instructions.forEachIndexed { messageIndex, instruction ->
            val occurrences = occurrencesPerMessage[messageIndex].flatten()
            matchedOccurrences += occurrences.size
            if (occurrences.isEmpty()) {
                newInstructions.add(instruction)
                return@forEachIndexed
            }
            val resolution = resolveConflicts(occurrences)
            appliedOccurrences += resolution.accepted.size
            resolution.rejected.forEach { (occurrence, reason) ->
                rejected.add(RejectedOccurrence(messageIndex, occurrence.editIndex, reason))
            }
            var message = instruction.message
            resolution.accepted.sortedByDescending { it.start }.forEach { occurrence ->
                message = message.replaceRange(
                    occurrence.start,
                    occurrence.endExclusive,
                    edits[occurrence.editIndex].newText
                )
            }
            newInstructions.add(instruction.copy(message = message))
        }
        EditOutcome(newInstructions, matchedOccurrences, appliedOccurrences, rejected)
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
     * Resolves overlapping occurrences inside one instruction message deterministically.
     *
     * The highest-priority occurrence (longest matched span; ties broken by lower edit index, then
     * earlier start) is accepted greedily, and every later candidate that overlaps an accepted one
     * is rejected. This mirrors the worker `edit_file` conflict policy so the outcome does not
     * depend on caller-supplied edit order. Rejected occurrences carry a human-readable reason
     * naming the kept conflicting occurrence so the report is unambiguous.
     *
     * @param occurrences All matched occurrence ranges of one message.
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
     * Renders the human-readable edit report: an edit summary (requested/matched/applied/rejected
     * occurrences, mirroring the worker `edit_file` report), the rejected-overlap details, and a
     * unified diff per changed instruction.
     *
     * The report is plain text (never JSON): the LLM sees exactly what changed and how many
     * occurrences were touched, without the full role payload. Only instructions whose message
     * actually changed produce a diff section; each section is labeled with the instruction's
     * list index, type, and name, and rendered with [LineDiff.unifiedDiff] (Git-style `@@` hunks
     * with 3 context lines). The complete report is capped at [MAX_REPORT_BYTES] UTF-8 bytes; when
     * the cap is hit, the diff body is truncated at a UTF-8 boundary and a notice tells the LLM to
     * use `read_agent_role` for the full picture.
     *
     * @param persisted The persisted (original) role: supplies the operation header (role name
     *            and id) and the original instruction texts the diff is computed against.
     * @param outcome The planned outcome (new instructions plus occurrence statistics).
     * @param edits The caller-supplied edit specs (for the requested count).
     * @return The rendered report string.
     */
    private fun renderReport(
        persisted: AgentRoleDto,
        outcome: EditOutcome,
        edits: List<TextEditSpec>,
    ): String {
        val summary = buildString {
            append("Edited instructions in agent role '").append(persisted.name)
                .append("' (id: ").append(persisted.id).append("):\n")
            append("Edit summary:\n")
            append("- requested edit specs: ").append(edits.size).append('\n')
            append("- matched occurrences: ").append(outcome.matchedOccurrences).append('\n')
            append("- applied occurrences: ").append(outcome.appliedOccurrences).append('\n')
            append("- rejected occurrences: ").append(outcome.rejected.size).append('\n')
        }
        val rejectedSection = buildString {
            if (outcome.rejected.isNotEmpty()) {
                append("Rejected occurrences (overlapping, lower priority):\n")
                for ((instructionIndex, editIndex, reason) in outcome.rejected) {
                    append("  - instruction ").append(instructionIndex)
                        .append(", edit spec index ").append(formatEditIndex(editIndex))
                        .append(": ").append(reason).append('\n')
                }
            }
        }
        val diffBody = buildString {
            outcome.newInstructions.forEachIndexed { index, modified ->
                val original = persisted.instructions[index]
                if (original.message == modified.message) return@forEachIndexed
                append("Instruction ").append(index).append(" (type=").append(original.type)
                    .append(", name=").append(original.name).append("):\n")
                append(LineDiff.unifiedDiff(original.message.split('\n'), modified.message.split('\n')))
                append('\n')
            }
        }
        val diffSection = diffBody.ifEmpty { "(no changes)\n" }
        val diffHeader = "--- diff ---\n"
        val completeReport = summary + rejectedSection + diffHeader + diffSection
        if (utf8ByteCount(completeReport) <= MAX_REPORT_BYTES) return completeReport

        val notice = "\n[Output truncated at $MAX_REPORT_BYTES bytes. " +
            "Use read_agent_role to inspect the full instruction list.]\n"
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
     * Formats an edit-array index for inclusion in a diagnostic message.
     *
     * Positive indices receive an explicit qualifier because the value refers to a zero-based
     * JSON-array position; index zero remains concise for the first edit.
     *
     * @param index Zero-based position of the edit in the caller-supplied array.
     * @return The index text, with `(0-based)` appended when [index] is greater than zero.
     */
    private fun formatEditIndex(index: Int): String =
        index.toString() + if (index > 0) " (0-based)" else ""

    /**
     * A single matched occurrence of an edit spec inside one instruction message.
     *
     * @property editIndex Index of the edit spec in the caller-supplied batch (for tie-breaking).
     * @property start Start offset (inclusive) in the instruction message.
     * @property endExclusive End offset (exclusive) in the instruction message.
     */
    private data class Occurrence(
        val editIndex: Int,
        val start: Int,
        val endExclusive: Int
    )

    /**
     * Result of [resolveConflicts] for one instruction message.
     *
     * @property accepted Disjoint occurrences to apply (in no particular order).
     * @property rejected Overlapping occurrences dropped during resolution, with reasons.
     */
    private data class ConflictResolution(
        val accepted: List<Occurrence>,
        val rejected: List<Pair<Occurrence, String>>
    )

    /**
     * An occurrence rejected during conflict resolution, positioned for the report.
     *
     * @property instructionIndex Index of the instruction message that contained the occurrence.
     * @property editIndex Edit spec index of the rejected occurrence.
     * @property reason Human-readable explanation identifying the kept conflicting occurrence.
     */
    private data class RejectedOccurrence(
        val instructionIndex: Int,
        val editIndex: Int,
        val reason: String
    )

    /**
     * Outcome of [applyEdits] on the whole instruction list.
     *
     * @property newInstructions The edited instruction DTOs (unchanged entries are reused as-is).
     * @property matchedOccurrences Total occurrences of all edit specs found across all messages.
     * @property appliedOccurrences Total occurrences actually applied after conflict resolution.
     * @property rejected The occurrences dropped during conflict resolution, for the report.
     */
    private data class EditOutcome(
        val newInstructions: List<AgentInstructionDto>,
        val matchedOccurrences: Int,
        val appliedOccurrences: Int,
        val rejected: List<RejectedOccurrence>
    )

    private companion object {
        /** Maximum UTF-8 byte size of a successful edit report. */
        const val MAX_REPORT_BYTES = 5_000
    }
}
