package eu.torvian.chatbot.server.service.core.error.instruction

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Logical failures of deleting an instruction row.
 */
sealed interface DeleteInstructionError {

    /**
     * No instruction row with that id is owned by the requesting user.
     *
     * @property instructionId The requested instruction id.
     */
    data class NotFound(val instructionId: Long) : DeleteInstructionError

    /**
     * At least one agent role still links the row, so deleting it would silently drop those links.
     *
     * @property instructionId The requested instruction id.
     * @property linkedRoleIds The roles that link the row, ascending. A refusal raised from the storage
     *            constraint reports the links visible to the deleting transaction, so it can be empty
     *            when the blocking link is not visible there.
     */
    data class LinkedToRoles(val instructionId: Long, val linkedRoleIds: List<Long>) : DeleteInstructionError
}

/**
 * Converts a [DeleteInstructionError] to its [ApiError] representation.
 *
 * A foreign instruction is reported identically to a nonexistent one, so ownership never leaks
 * through the error surface. A still-linked row is a conflict rather than a failure: the caller can
 * unlink it first, and the detail names the blocking roles.
 */
fun DeleteInstructionError.toApiError(): ApiError = when (this) {
    is DeleteInstructionError.NotFound ->
        apiError(
            CommonApiErrorCodes.NOT_FOUND,
            "Instruction not found",
            "instructionId" to instructionId.toString()
        )

    is DeleteInstructionError.LinkedToRoles ->
        apiError(
            CommonApiErrorCodes.RESOURCE_IN_USE,
            "Instruction is still used by agent roles",
            "instructionId" to instructionId.toString(),
            "roleIds" to linkedRoleIds.joinToString()
        )
}