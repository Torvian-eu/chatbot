package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.SearchApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.core.ChatMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Unit tests for [DefaultSearchRepository].
 */
class DefaultSearchRepositoryTest {
    @Test
    fun `searchMessages caches successful search results`() = runTest {
        val api = mockk<SearchApi>()
        val repository = DefaultSearchRepository(api)
        val expected = listOf(messageSearchResult(messageId = 11L))
        coEvery { api.searchMessages("hello") } returns Either.Right(expected)

        val result = repository.searchMessages("hello")

        assertIs<Either.Right<Unit>>(result)
        val state = repository.searchResults.value
        assertIs<DataState.Success<List<MessageSearchResult>>>(state)
        assertEquals(expected, state.data)
        coVerify(exactly = 1) { api.searchMessages("hello") }
    }

    @Test
    fun `searchMessages maps api failures into repository errors`() = runTest {
        val api = mockk<SearchApi>()
        val repository = DefaultSearchRepository(api)
        val apiError = ApiResourceError.ServerError(
            apiError = apiError(CommonApiErrorCodes.NOT_FOUND, "Search endpoint failure")
        )
        coEvery { api.searchMessages("hello") } returns Either.Left(apiError)

        val result = repository.searchMessages("hello")

        assertIs<Either.Left<RepositoryError>>(result)
        val state = repository.searchResults.value
        assertIs<DataState.Error<RepositoryError>>(state)
        assertTrue(state.error.message.contains("Failed to search messages"))
    }

    @Test
    fun `clearSearch resets repository state to idle`() = runTest {
        val api = mockk<SearchApi>()
        val repository = DefaultSearchRepository(api)
        coEvery { api.searchMessages("hello") } returns Either.Right(listOf(messageSearchResult()))

        repository.searchMessages("hello")
        repository.clearSearch()

        assertEquals(DataState.Idle, repository.searchResults.value)
    }

    /**
     * Builds a cross-session search result fixture for repository tests.
     *
     * @param messageId Identifier of the matching message.
     * @return Search result fixture with stable metadata.
     */
    private fun messageSearchResult(messageId: Long = 1L): MessageSearchResult = MessageSearchResult(
        sessionId = 7L,
        sessionName = "Session $messageId",
        messageId = messageId,
        messageRole = ChatMessage.Role.USER,
        snippet = "hello from session $messageId",
        matchStartIndex = 0,
        matchEndExclusive = 5,
        createdAt = Clock.System.now(),
    )
}