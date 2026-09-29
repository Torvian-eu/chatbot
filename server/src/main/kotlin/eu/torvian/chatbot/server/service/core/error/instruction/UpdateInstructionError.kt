package eu.torvian.chatbot.server.service.core.error.instruction

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Logical failures of updating an instruction row's content.
 */
sealed interface UpdateInstructionError {
    /**
     * No instruction row with that id is owned by the requesting user.
     *
     * @property instructionId The requested instruction id.
     */
    data class NotFound(val instructionId: Long) : UpdateInstructionError

    /**
     * The authored content is not a legal instruction.
     *
     * @property reason Human-readable description of the violated rule.
     */
    data class ValidationFailed(val reason: String) : UpdateInstructionError

    /**
     * Changing the row's kind or model target would leave at least one linking agent role with an
     * invalid instruction list.
     *
     * The row is shared content, so its kind is part of every linking role's list validity; the change
     * is allowed only when every linking role's resulting list still satisfies the per-role rules.
     *
     * @property instructionId The row whose content was to change.
     * @property linkedRoleIds The roles whose resulting lists would be invalid, ascending.
     * @property reason Human-readable description of the violated rule.
     */
    data class LinkedRoleInstructionListInvalid(
        val instructionId: Long,
        val linkedRoleIds: List<Long>,
        val reason: String
    ) : UpdateInstructionError
}

/**
 * Converts an [UpdateInstructionError] to its [ApiError] representation.
 *
 * A foreign instruction is reported identically to a nonexistent one, so ownership never leaks
 * through the error surface.
 */
fun UpdateInstructionError.toApiError(): ApiError = when (this) {
    is UpdateInstructionError.NotFound ->
        apiError(
            CommonApiErrorCodes.NOT_FOUND,
            "Instruction not found",
            "instructionId" to instructionId.toString()
        )

    is UpdateInstructionError.ValidationFailed ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid instruction: $reason")

    is UpdateInstructionError.LinkedRoleInstructionListInvalid ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Instruction change would leave agent role(s) ${linkedRoleIds.joinToString()} with an " +
                "invalid instruction list: $reason",
            "instructionId" to instructionId.toString(),
            "roleIds" to linkedRoleIds.joinToString()
        )
}
