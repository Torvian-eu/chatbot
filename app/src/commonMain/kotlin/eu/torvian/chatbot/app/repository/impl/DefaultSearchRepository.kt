package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SearchRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.SearchApi
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope
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
     * Monotonic request sequence used to suppress stale completions when searches overlap.
     */
    private var latestRequestSequence: Long = 0

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
     * @param scope Search scope requested by the UI.
     * @return Either the mapped repository failure or [Unit] when state updates finished.
     */
    override suspend fun searchMessages(
        query: String,
        scope: MessageSearchScope,
    ): Either<RepositoryError, Unit> {
        val requestSequence = beginSearchRequest()
        _searchResults.update { DataState.Loading }
        return searchApi.searchMessages(query, scope).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to search messages")
                if (requestSequence != latestRequestSequence) {
                    // A newer request already superseded this failure, so keep the fresher result state
                    // and suppress an obsolete error return to the caller.
                    Unit.right()
                } else {
                    _searchResults.update { DataState.Error(repositoryError) }
                    repositoryError.left()
                }
            },
            ifRight = { results ->
                if (requestSequence == latestRequestSequence) {
                    _searchResults.update { DataState.Success(results) }
                }
                Unit.right()
            }
        )
    }

    /**
     * Resets the cached search result state to idle.
     */
    override fun clearSearch() {
        latestRequestSequence += 1
        _searchResults.value = DataState.Idle
    }


    /**
     * Records a new latest-request token before launching the API call.
     *
     * @return Sequence number that uniquely identifies the in-flight request.
     */
    private fun beginSearchRequest(): Long {
        latestRequestSequence += 1
        return latestRequestSequence
    }
}