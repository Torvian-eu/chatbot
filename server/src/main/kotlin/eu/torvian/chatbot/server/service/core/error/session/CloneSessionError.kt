package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors during the cloning of a chat session.
 */
sealed interface CloneSessionError {
    /**
     * Indicates that the session to clone was not found.
     */
    data class SessionNotFound(val id: Long) : CloneSessionError

    /**
     * Indicates that the provided name is invalid (e.g., blank).
     */
    data class InvalidName(val reason: String) : CloneSessionError

    /**
     * Indicates that an internal error occurred during the cloning process.
     */
    data class InternalError(val message: String) : CloneSessionError
}

fun CloneSessionError.toApiError(): ApiError = when (this) {
    is CloneSessionError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )

    is CloneSessionError.InvalidName -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid session name provided",
        "reason" to reason
    )

    is CloneSessionError.InternalError -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Failed to clone session",
        "details" to message
    )
}

