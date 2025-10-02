package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when updating a chat session's name.
 */
sealed interface UpdateSessionNameError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionNameError
    /**
     * Indicates that the provided new name is invalid (e.g., blank).
     */
    data class InvalidName(val reason: String) : UpdateSessionNameError
}

fun UpdateSessionNameError.toApiError(): ApiError = when (this) {
    is UpdateSessionNameError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )

    is UpdateSessionNameError.InvalidName -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid session name provided",
        "reason" to reason
    )
}
