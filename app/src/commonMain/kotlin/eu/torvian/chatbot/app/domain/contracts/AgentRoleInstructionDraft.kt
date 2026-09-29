package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelIdOrNull
import eu.torvian.chatbot.common.models.api.agent.InstructionSlot
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import kotlinx.serialization.json.JsonObject

/**
 * Draft of one instruction row inside the agent-role form.
 *
 * Saving the role sends every draft as one entry of the role's ordered link set ([toSlot]): a new
 * draft creates and links a row inline, an edited stored draft replaces and links its row, and an
 * untouched stored draft is only referenced. [id] is null for a row the user added in the form;
 * [original] holds the content the linked row had when the draft was built, which is what keeps an
 * untouched row from being rewritten.
 *
 * @property id Identifier of the linked instruction row, or null for a row that does not exist yet.
 * @property type The [AgentInstructionTypes] key of this draft's kind.
 * @property name Human-readable label of the instruction.
 * @property message Instruction text. A `spawnable_agents` row carries only generated text, which is
 *            never stored.
 * @property custom Type-specific extra fields (e.g. `{"modelId": 5}` for `model_specific`); null for
 *            kinds that carry no extra data.
 * @property linkedRoleIds Ids of the agent roles that link the row, as last reported by the server.
 * @property original Content of the linked row when the draft was built, or null for a row that does
 *            not exist yet.
 */
data class AgentRoleInstructionDraft(
    val id: Long? = null,
    val type: String,
    val name: String,
    val message: String,
    val custom: JsonObject? = null,
    val linkedRoleIds: Set<Long> = emptySet(),
    val original: AgentInstructionDto? = null
) {

    /**
     * Whether saving the role must write this row's content.
     *
     * A row that does not exist yet is always written; a linked row is written only when the authored
     * content differs from [original], so an untouched shared row is left alone.
     */
    val needsWrite: Boolean
        get() {
            val previous = original ?: return true
            return previous.type != type ||
                previous.name != name ||
                previous.custom != custom ||
                storedMessage(previous.type, previous.message) != storedMessage(type, message)
        }

    /**
     * Whether more than one agent role links this draft's row.
     *
     * Mirrors the derived flag of a reported instruction: a shared draft's content reaches every linked
     * role when it is saved, which is what the form marks before the user edits anything.
     */
    val shared: Boolean
        get() = linkedRoleIds.size > 1

    /**
     * Expresses this draft's authored content as an instruction create request (the content of an
     * inline create slot).
     *
     * @return The request carrying the draft's authored content.
     */
    fun toCreateRequest(): CreateInstructionRequest = CreateInstructionRequest(
        type = type,
        name = name,
        message = storedMessage(type, message),
        custom = custom
    )

    /**
     * Expresses this draft as a full-replacement update request.
     *
     * The row to rewrite travels in the request itself, so a caller that already knows the draft is
     * stored passes no id of its own.
     *
     * @return The request carrying the draft's row id and authored content, or null when the draft is
     *         not backed by a stored row yet (it has to be created first).
     */
    fun toUpdateRequest(): UpdateInstructionRequest? = id?.let { instructionId ->
        UpdateInstructionRequest(
            id = instructionId,
            type = type,
            name = name,
            message = storedMessage(type, message),
            custom = custom
        )
    }

    /**
     * Expresses this draft as one entry of the role's ordered link set.
     *
     * A row that does not exist yet is created and linked inline; an edited stored row is replaced and
     * linked; an untouched stored row is only referenced, so a save never rewrites content the user
     * did not change.
     *
     * @return The slot describing what saving the role does to this draft's row.
     */
    fun toSlot(): InstructionSlot = when {
        id == null -> InstructionSlot.Create(toCreateRequest())
        needsWrite -> InstructionSlot.Update(checkNotNull(toUpdateRequest()))
        else -> InstructionSlot.Link(id)
    }

    /**
     * The model a `model_specific` draft targets.
     *
     * @return The target model id, or null when the draft carries none or a malformed value.
     */
    fun modelSpecificId(): Long? = custom.modelIdOrNull()
}

/**
 * The message an instruction write stores for one kind.
 *
 * A `spawnable_agents` row keeps no text of its own — the server regenerates it per linked role — so
 * the value is dropped rather than written, and it is discounted when content is compared.
 *
 * @param type The instruction kind key.
 * @param message The authored message text.
 * @return The value to store, or null for the generated-message kind.
 */
private fun storedMessage(type: String, message: String): String? =
    if (type == AgentInstructionTypes.SPAWNABLE_AGENTS) null else message

/**
 * Expresses a reported instruction as a form draft that keeps its identity.
 *
 * Both report sites are covered: a role's instruction list and the user's library. Saving an untouched
 * draft then rewrites the same row instead of creating a copy of it, which is also what links a picked
 * library row as it is.
 *
 * A library row of a generated-message kind carries an empty message, because no role context exists
 * to generate text from; an untouched draft is still left alone, since that kind's stored form is empty
 * either way.
 *
 * @receiver The reported instruction to edit.
 * @return The equivalent draft, linked to [AgentInstructionDto.id].
 */
fun AgentInstructionDto.toDraft(): AgentRoleInstructionDraft = AgentRoleInstructionDraft(
    id = id,
    type = type,
    name = name,
    message = message,
    custom = custom,
    linkedRoleIds = linkedRoleIds,
    original = this
)
