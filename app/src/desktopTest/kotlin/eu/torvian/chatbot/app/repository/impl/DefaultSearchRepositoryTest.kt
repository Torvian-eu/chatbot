package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.SearchApi
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Tests latest-request-wins behavior in [DefaultSearchRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSearchRepositoryTest {
    /** Stable timestamp reused by all fixtures in this test suite. */
    private val now = Clock.System.now()

    /**
     * Ensures a later request can replace an earlier in-flight request without stale completion winning.
     */
    @Test
    fun `newer search response wins when requests overlap`() = runTest {
        val firstResponse = CompletableDeferred<Either<ApiResourceError, List<MessageSearchResult>>>()
        val secondResponse = CompletableDeferred<Either<ApiResourceError, List<MessageSearchResult>>>()
        val recordedRequests = mutableListOf<Pair<String, MessageSearchScope>>()
        var requestCount = 0

        val repository = DefaultSearchRepository(
            searchApi = object : SearchApi {
                override suspend fun searchMessages(
                    query: String,
                    scope: MessageSearchScope,
                ): Either<ApiResourceError, List<MessageSearchResult>> {
                    recordedRequests += query to scope
                    return when (++requestCount) {
                        1 -> firstResponse.await()
                        2 -> secondResponse.await()
                        else -> error("Unexpected search request #$requestCount")
                    }
                }
            }
        )

        val firstRequest = async {
            repository.searchMessages("alpha", MessageSearchScope.VISIBLE_THREADS_ONLY)
        }
        advanceUntilIdle()
        assertTrue(repository.searchResults.value.isLoading)

        val secondRequest = async {
            repository.searchMessages("beta", MessageSearchScope.ALL_THREADS)
        }
        advanceUntilIdle()
        assertTrue(repository.searchResults.value.isLoading)

        secondResponse.complete(listOf(searchResult(messageId = 2L)).right())
        advanceUntilIdle()

        val latestState = assertIs<DataState.Success<List<MessageSearchResult>>>(repository.searchResults.value)
        assertEquals(listOf(searchResult(messageId = 2L)), latestState.data)
        assertIs<Either.Right<Unit>>(secondRequest.await())

        firstResponse.complete(listOf(searchResult(messageId = 1L)).right())
        advanceUntilIdle()

        val staleCompletionState = assertIs<DataState.Success<List<MessageSearchResult>>>(repository.searchResults.value)
        assertEquals(listOf(searchResult(messageId = 2L)), staleCompletionState.data)
        assertIs<Either.Right<Unit>>(firstRequest.await())
        assertEquals(
            listOf(
                "alpha" to MessageSearchScope.VISIBLE_THREADS_ONLY,
                "beta" to MessageSearchScope.ALL_THREADS,
            ),
            recordedRequests,
        )
    }

    /**
     * Verifies clearing cached state also supersedes any older in-flight request.
     */
    @Test
    fun `clearSearch suppresses late completion from an older request`() = runTest {
        val pendingResponse = CompletableDeferred<Either<ApiResourceError, List<MessageSearchResult>>>()
        val repository = DefaultSearchRepository(
            searchApi = object : SearchApi {
                override suspend fun searchMessages(
                    query: String,
                    scope: MessageSearchScope,
                ): Either<ApiResourceError, List<MessageSearchResult>> = pendingResponse.await()
            }
        )

        val request = async {
            repository.searchMessages("alpha", MessageSearchScope.VISIBLE_THREADS_ONLY)
        }
        advanceUntilIdle()
        assertTrue(repository.searchResults.value.isLoading)

        repository.clearSearch()
        assertIs<DataState.Idle>(repository.searchResults.value)

        pendingResponse.complete(listOf(searchResult(messageId = 3L)).right())
        advanceUntilIdle()

        assertIs<DataState.Idle>(repository.searchResults.value)
        assertIs<Either.Right<Unit>>(request.await())
    }

    /**
     * Builds a deterministic cross-session search result fixture for repository tests.
     *
     * @param messageId Identifier of the matching message.
     * @return Search result carrying stable metadata for assertions.
     */
    private fun searchResult(messageId: Long): MessageSearchResult = MessageSearchResult(
        sessionId = 9L,
        sessionName = "Session 9",
        messageId = messageId,
        messageRole = ChatMessage.Role.ASSISTANT,
        snippet = "alpha beta",
        matchStartIndex = 0,
        matchEndExclusive = 5,
        createdAt = now,
    )
}