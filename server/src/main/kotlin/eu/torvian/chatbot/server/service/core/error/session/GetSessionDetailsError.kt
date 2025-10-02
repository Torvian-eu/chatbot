package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when retrieving detailed information for a chat session.
 */
sealed interface GetSessionDetailsError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : GetSessionDetailsError
}

fun GetSessionDetailsError.toApiError(): ApiError = when (this) {
    is GetSessionDetailsError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )
}
