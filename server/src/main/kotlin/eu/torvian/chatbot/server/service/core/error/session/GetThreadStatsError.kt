package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when computing the statistics of a session's thread.
 */
sealed interface GetThreadStatsError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : GetThreadStatsError
}

/**
 * Maps a thread-stats failure to the API error body returned to the client.
 *
 * @receiver The failure to translate.
 * @return The API error carrying the status code and the shared error code.
 */
fun GetThreadStatsError.toApiError(): ApiError = when (this) {
    is GetThreadStatsError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )
}
