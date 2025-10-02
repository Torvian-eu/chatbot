package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Represents possible errors when deleting a chat session.
 */
sealed interface DeleteSessionError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : DeleteSessionError
}

fun DeleteSessionError.toApiError(): ApiError = when (this) {
    is DeleteSessionError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )
}
