package eu.torvian.chatbot.common.models.api.instruction

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for creating a new instruction row.
 *
 * Instructions are first-class objects: they are authored here (and through the instruction tools)
 * and shared across agent roles by id. A role write may carry this same request inline
 * ([eu.torvian.chatbot.common.models.api.agent.InstructionSlot.Create]), so the content contract has
 * exactly one home. The caller becomes the owner of the created row.
 *
 * @property type The [AgentInstructionTypes] key of this instruction kind.
 * @property name Human-readable label of the instruction, non-blank and at most 255 characters.
 * @property message Instruction text, or null for the generated-message kind
 *            ([AgentInstructionTypes.SPAWNABLE_AGENTS]), whose text is produced per linked role at
 *            read time.
 * @property custom Type-specific extra fields (e.g. `{"modelId": 5}` for `model_specific`); null for
 *            kinds that carry no extra data.
 */
@Serializable
data class CreateInstructionRequest(
    val type: String,
    val name: String,
    val message: String? = null,
    val custom: JsonObject? = null
)
