package eu.torvian.chatbot.server.service.core.error.search

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Logical validation failures for cross-session message search requests.
 */
sealed interface SearchMessagesError {
    /**
     * Indicates that the provided query was blank after trimming.
     */
    data object EmptyQuery : SearchMessagesError

    /**
     * Indicates that the provided query exceeded the accepted maximum length.
     *
     * @property actualLength Number of characters supplied by the caller after trimming.
     * @property maxLength Maximum accepted query length.
     */
    data class QueryTooLong(
        val actualLength: Int,
        val maxLength: Int
    ) : SearchMessagesError
}

/**
 * Converts a [SearchMessagesError] into a transport-safe API error.
 */
fun SearchMessagesError.toApiError(): ApiError = when (this) {
    SearchMessagesError.EmptyQuery ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Search query cannot be blank")

    is SearchMessagesError.QueryTooLong ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Search query is too long",
            "actualLength" to actualLength.toString(),
            "maxLength" to maxLength.toString()
        )
}