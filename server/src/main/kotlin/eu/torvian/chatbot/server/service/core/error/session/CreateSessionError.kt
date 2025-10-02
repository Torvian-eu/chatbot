package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors during the creation of a chat session.
 */
sealed interface CreateSessionError {
    /**
     * Indicates that the provided name is invalid (e.g., blank).
     */
    data class InvalidName(val reason: String) : CreateSessionError
    /**
     * Indicates that a foreign key constraint failed during insertion (e.g., groupId, modelId, settingsId not found).
     * Maps from SessionError.ForeignKeyViolation in the DAO layer.
     */
    data class InvalidRelatedEntity(val message: String) : CreateSessionError
}

fun CreateSessionError.toApiError(): ApiError = when (this) {
    is CreateSessionError.InvalidName -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid session name provided",
        "reason" to reason
    )

    is CreateSessionError.InvalidRelatedEntity -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid related entity ID provided",
        "details" to message
    )
}
