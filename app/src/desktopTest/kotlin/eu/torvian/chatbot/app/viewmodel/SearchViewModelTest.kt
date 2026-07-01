package eu.torvian.chatbot.app.viewmodel

import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SearchRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.MessageSearchResult
import eu.torvian.chatbot.common.models.api.core.MessageSearchScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Unit tests for [CrossSessionSearchViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossSessionSearchViewModelTest {
    @Test
    fun `performSearch trims the query and forwards it to the repository`() = runTest {
        val repository = mockk<SearchRepository>()
        val searchNavigationCoordinator = mockk<SearchNavigationCoordinator>(relaxed = true)
        val notificationService = mockk<NotificationService>(relaxed = true)
        val searchResults = MutableStateFlow<DataState<RepositoryError, List<MessageSearchResult>>>(
            DataState.Success(listOf(messageSearchResult()))
        )
        every { repository.searchResults } returns searchResults
        coEvery { repository.searchMessages("hello", MessageSearchScope.ALL_THREADS) } returns Unit.right()
        val viewModel = CrossSessionSearchViewModel(
            searchRepository = repository,
            searchNavigationCoordinator = searchNavigationCoordinator,
            notificationService = notificationService,
            uiDispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.showSearchDialog()
        viewModel.updateSearchQuery("  hello  ")
        viewModel.updateSearchScope(MessageSearchScope.ALL_THREADS)
        viewModel.performSearch()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isDialogVisible)
        assertEquals("hello", state.draftQuery)
        assertEquals("hello", state.submittedQuery)
        assertEquals(MessageSearchScope.ALL_THREADS, state.draftScope)
        assertEquals(MessageSearchScope.ALL_THREADS, state.submittedScope)
        assertIs<DataState.Success<List<MessageSearchResult>>>(state.resultsState)
        coVerify(exactly = 1) { repository.searchMessages("hello", MessageSearchScope.ALL_THREADS) }
    }

    @Test
    fun `hideSearchDialog preserves previously loaded results and selected scope`() = runTest {
        val repository = mockk<SearchRepository>()
        val searchNavigationCoordinator = mockk<SearchNavigationCoordinator>(relaxed = true)
        val notificationService = mockk<NotificationService>(relaxed = true)
        every {
            repository.searchResults
        } returns MutableStateFlow<DataState<RepositoryError, List<MessageSearchResult>>>(
            DataState.Success(listOf(messageSearchResult(messageId = 22L)))
        )
        val viewModel = CrossSessionSearchViewModel(
            searchRepository = repository,
            searchNavigationCoordinator = searchNavigationCoordinator,
            notificationService = notificationService,
            uiDispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.showSearchDialog()
        viewModel.updateSearchScope(MessageSearchScope.ALL_THREADS)
        viewModel.hideSearchDialog()
        viewModel.showSearchDialog()

        val state = viewModel.uiState.value
        assertTrue(state.isDialogVisible)
        assertEquals(MessageSearchScope.ALL_THREADS, state.draftScope)
        assertIs<DataState.Success<List<MessageSearchResult>>>(state.resultsState)
    }

    @Test
    fun `performSearch with blank input shows warning and skips repository`() = runTest {
        val repository = mockk<SearchRepository>()
        val searchNavigationCoordinator = mockk<SearchNavigationCoordinator>(relaxed = true)
        val notificationService = mockk<NotificationService>(relaxed = true)
        every {
            repository.searchResults
        } returns MutableStateFlow<DataState<RepositoryError, List<MessageSearchResult>>>(DataState.Idle)
        val viewModel = CrossSessionSearchViewModel(
            searchRepository = repository,
            searchNavigationCoordinator = searchNavigationCoordinator,
            notificationService = notificationService,
            uiDispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.updateSearchQuery("   ")
        viewModel.performSearch()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.searchMessages(any(), any()) }
        coVerify(exactly = 1) { notificationService.genericWarning("Enter a search query.") }
    }

    /**
     * Builds a cross-session search result fixture for ViewModel tests.
     *
     * @param messageId Identifier of the matching message.
     * @return Search result fixture with stable metadata.
     */
    private fun messageSearchResult(messageId: Long = 1L): MessageSearchResult = MessageSearchResult(
        sessionId = 8L,
        sessionName = "Session $messageId",
        messageId = messageId,
        messageRole = ChatMessage.Role.ASSISTANT,
        snippet = "hello from session $messageId",
        matchStartIndex = 0,
        matchEndExclusive = 5,
        createdAt = Clock.System.now(),
    )
}