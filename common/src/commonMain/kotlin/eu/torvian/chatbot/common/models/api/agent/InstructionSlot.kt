package eu.torvian.chatbot.common.models.api.agent

import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of an agent-role write's ordered instruction link set.
 *
 * The three variants are mutually exclusive alternatives — reference a stored row, insert one, or
 * replace one — so the sum is strictly typed and every field is required per variant: no combination
 * of nullable flags can express an illegal state. Each content variant wraps verbatim the request its
 * operation would take on the instruction endpoints, so the instruction content contract has exactly
 * one home. The server materializes the whole list in one transaction with the role row, which is why
 * a failed role save cannot leave partially written instruction rows behind.
 *
 * The polymorphic tag rides on the default `type` key of the slot object; the wrapped instruction
 * content nests its own `type` field inside `content`, so the two never share a JSON object.
 */
@Serializable
sealed interface InstructionSlot {

    /**
     * References an existing instruction row without touching its content.
     *
     * A concurrent content edit of the row therefore survives the role save; only the link is
     * (re)written.
     *
     * @property id Identifier of the owned instruction row to link.
     */
    @Serializable
    @SerialName("link")
    data class Link(val id: Long) : InstructionSlot

    /**
     * Creates a new instruction row from [content] and links it as part of the same save.
     *
     * @property content The full create request for the new row, validated exactly like the
     *            instruction create endpoint.
     */
    @Serializable
    @SerialName("create")
    data class Create(val content: CreateInstructionRequest) : InstructionSlot

    /**
     * Replaces the content of an existing instruction row and links it as part of the same save.
     *
     * The content reaches every agent role linking the row (shared-row semantics). The target row is
     * named by [UpdateInstructionRequest.id], as on the instruction update endpoint.
     *
     * @property content The full update request naming the row to rewrite and its new content,
     *            validated exactly like the instruction update endpoint.
     */
    @Serializable
    @SerialName("update")
    data class Update(val content: UpdateInstructionRequest) : InstructionSlot
}
