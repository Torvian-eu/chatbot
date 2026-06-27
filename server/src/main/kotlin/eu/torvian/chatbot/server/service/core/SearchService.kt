package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.server.service.core.error.search.SearchMessagesError

/**
 * Service interface for cross-session search operations owned by the authenticated user.
 */
interface SearchService {
    /**
     * Searches message content across all sessions owned by [userId].
     *
     * @param userId Authenticated user whose sessions should be searched.
     * @param query Search string to match literally.
     * @param limit Maximum number of matches to return. Defaults to `50`.
     * @return Either a validation error or a list of matching messages ordered by recency.
     */
    suspend fun searchMessages(
        userId: Long,
        query: String,
        limit: Int = 50
    ): Either<SearchMessagesError, List<MessageSearchResult>>
}