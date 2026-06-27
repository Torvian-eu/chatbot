package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.service.core.SearchService
import eu.torvian.chatbot.server.service.core.error.search.SearchMessagesError

/**
 * Default implementation of [SearchService].
 *
 * Query validation stays in the service layer so route handlers and other callers share one consistent set
 * of business rules before the DAO performs the actual ownership-scoped search.
 */
class SearchServiceImpl(
    /**
     * Message data access dependency used to execute the ownership-scoped search.
     */
    private val messageDao: MessageDao
) : SearchService {

    companion object {
        /**
         * Upper bound for literal search queries accepted by the service.
         */
        private const val MAX_QUERY_LENGTH: Int = 200

        /**
         * Highest result limit the service will allow callers to request.
         */
        private const val MAX_RESULT_LIMIT: Int = 100
    }

    override suspend fun searchMessages(
        userId: Long,
        query: String,
        scope: MessageSearchScope,
        limit: Int,
    ): Either<SearchMessagesError, List<MessageSearchResult>> = either {
        val normalizedQuery = query.trim()
        ensure(normalizedQuery.isNotEmpty()) { SearchMessagesError.EmptyQuery }
        ensure(normalizedQuery.length <= MAX_QUERY_LENGTH) {
            SearchMessagesError.QueryTooLong(normalizedQuery.length, MAX_QUERY_LENGTH)
        }

        messageDao.searchMessagesByUserId(
            userId = userId,
            query = normalizedQuery,
            scope = scope,
            limit = limit.coerceIn(1, MAX_RESULT_LIMIT),
        )
    }
}