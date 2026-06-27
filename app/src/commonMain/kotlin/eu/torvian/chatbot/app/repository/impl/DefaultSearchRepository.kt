package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SearchRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.SearchApi
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default [SearchRepository] implementation backed by [SearchApi].
 *
 * @property searchApi API client used to execute server-backed searches.
 */
class DefaultSearchRepository(
    private val searchApi: SearchApi
) : SearchRepository {

    /**
     * Mutable backing state storing the latest search request result.
     */
    private val _searchResults = MutableStateFlow<DataState<RepositoryError, List<MessageSearchResult>>>(DataState.Idle)

    /**
     * Public read-only view of the latest search request result.
     */
    override val searchResults: StateFlow<DataState<RepositoryError, List<MessageSearchResult>>> =
        _searchResults.asStateFlow()

    /**
     * Executes a new search request and updates [searchResults] with loading, success, or error
     * states so the UI can render progress and preserve previous responses.
     *
     * @param query Literal query string to search for.
     * @return Either the mapped repository failure or [Unit] when state updates finished.
     */
    override suspend fun searchMessages(query: String): Either<RepositoryError, Unit> {
        if (_searchResults.value.isLoading) {
            return Unit.right()
        }

        _searchResults.update { DataState.Loading }
        return searchApi.searchMessages(query)
            .map { results ->
                _searchResults.update { DataState.Success(results) }
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to search messages")
                _searchResults.update { DataState.Error(repositoryError) }
                repositoryError
            }
    }

    /**
     * Resets the cached search result state to idle.
     */
    override fun clearSearch() {
        _searchResults.value = DataState.Idle
    }
}