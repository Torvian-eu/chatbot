package eu.torvian.chatbot.server.service.core.error.instruction

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Logical failures of reading a single instruction row.
 */
sealed interface GetInstructionError {

    /**
     * No instruction row with that id is owned by the requesting user.
     *
     * @property instructionId The requested instruction id.
     */
    data class NotFound(val instructionId: Long) : GetInstructionError
}

/**
 * Converts a [GetInstructionError] to its [ApiError] representation.
 *
 * A foreign instruction is reported identically to a nonexistent one, so ownership never leaks
 * through the error surface.
 */
fun GetInstructionError.toApiError(): ApiError = when (this) {
    is GetInstructionError.NotFound ->
        apiError(
            CommonApiErrorCodes.NOT_FOUND,
            "Instruction not found",
            "instructionId" to instructionId.toString()
        )
}
