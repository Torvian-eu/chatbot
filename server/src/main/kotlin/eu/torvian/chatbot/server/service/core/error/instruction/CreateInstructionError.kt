package eu.torvian.chatbot.server.service.core.error.instruction

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Logical failures of creating an instruction row.
 */
sealed interface CreateInstructionError {
    /**
     * The authored content is not a legal instruction.
     *
     * @property reason Human-readable description of the violated rule.
     */
    data class ValidationFailed(val reason: String) : CreateInstructionError

    /**
     * The ownership row of the new instruction could not be written.
     *
     * @property reason Human-readable description of the persistence failure.
     */
    data class OwnerInsertFailed(val reason: String) : CreateInstructionError
}

/**
 * Converts a [CreateInstructionError] to its [ApiError] representation.
 */
fun CreateInstructionError.toApiError(): ApiError = when (this) {
    is CreateInstructionError.ValidationFailed ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid instruction: $reason")

    is CreateInstructionError.OwnerInsertFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Failed to set instruction ownership: $reason")
}
