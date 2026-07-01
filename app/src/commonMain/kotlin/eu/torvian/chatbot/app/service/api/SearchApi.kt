package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope

/**
 * Frontend API interface for cross-session search endpoints.
 *
 * Implementations delegate to the authenticated backend API and return typed failures using
 * [ApiResourceError] so repository code can map them into UI-facing errors.
 */
interface SearchApi {
    /**
     * Searches message content across all sessions owned by the authenticated user.
     *
     * Corresponds to `GET /api/v1/search/messages?query=...`.
     *
     * @param query Literal query string that should be matched by the backend.
     * @param scope Search scope controlling whether only visible threads or all threads are searched.
     * @return Either the API error or the ordered list of matching messages.
     */
    suspend fun searchMessages(
        query: String,
        scope: MessageSearchScope = MessageSearchScope.VISIBLE_THREADS_ONLY,
    ): Either<ApiResourceError, List<MessageSearchResult>>
}