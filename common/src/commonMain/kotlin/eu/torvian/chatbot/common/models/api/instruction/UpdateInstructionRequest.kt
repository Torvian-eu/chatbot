package eu.torvian.chatbot.common.models.api.instruction

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Request body for replacing the content of an existing instruction row.
 *
 * The write is a full replacement of the row's authored content and therefore reaches every agent
 * role that links it; the links themselves are managed by the role payloads, not here. The row to
 * rewrite is named by [id], so the target travels with the content instead of alongside it as a
 * separate argument.
 *
 * @property id Identifier of the instruction row to replace. A missing or foreign row collapses to
 *            the same not-found error, so the caller's error surface stays uniform.
 * @property type The [AgentInstructionTypes] key of this instruction kind.
 * @property name Human-readable label of the instruction, non-blank and at most 255 characters.
 * @property message Instruction text, or null for the generated-message kind
 *            ([AgentInstructionTypes.SPAWNABLE_AGENTS]), whose text is produced per linked role at
 *            read time.
 * @property custom Type-specific extra fields (e.g. `{"modelId": 5}` for `model_specific`); null for
 *            kinds that carry no extra data.
 */
@Serializable
data class UpdateInstructionRequest(
    val id: Long,
    val type: String,
    val name: String,
    val message: String? = null,
    val custom: JsonObject? = null
)
