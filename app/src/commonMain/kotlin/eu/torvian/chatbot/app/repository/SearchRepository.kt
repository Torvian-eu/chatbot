package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository contract for cross-session message search.
 *
 * The repository stores the latest search result state so the dialog can be closed and reopened
 * without losing previously fetched results.
 */
interface SearchRepository {
    /**
     * Reactive state of the latest cross-session search request.
     */
    val searchResults: StateFlow<DataState<RepositoryError, List<MessageSearchResult>>>

    /**
     * Performs a new server-backed cross-session message search.
     *
     * @param query Literal query string to search for.
     * @return Either the mapped repository failure or [Unit] when the state was updated successfully.
     */
    suspend fun searchMessages(query: String): Either<RepositoryError, Unit>

    /**
     * Clears the cached result state and returns the repository to its idle state.
     */
    fun clearSearch()
}