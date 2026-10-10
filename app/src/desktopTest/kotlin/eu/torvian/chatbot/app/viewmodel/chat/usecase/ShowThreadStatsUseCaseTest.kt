package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.common.models.api.core.DurationStats
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests the load lifecycle of the thread statistics dialog.
 *
 * The use case owns the fetch, so the assertions cover what the dialog is opened with, what the load
 * publishes, and what a retry does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowThreadStatsUseCaseTest {

    private val sessionId = 7L

    private lateinit var sessionRepository: SessionRepository
    private lateinit var state: ChatState
    private lateinit var activeSessionId: MutableStateFlow<Long?>

    private val stats = ThreadStats(
        totalMessages = 2,
        userMessages = 1,
        assistantMessages = 1,
        toolCalls = 0,
        reasoningItems = 0,
        inputTokens = 10L,
        outputTokens = 5L,
        reasoningTokens = 0L,
        cachedInputTokens = 0L,
        responseDuration = DurationStats(1_000L, 1_000.0, 0.0, emptyList()),
        toolCallDuration = DurationStats(0L, null, null, emptyList()),
        toolCallSuccessRate = null,
        assistantMessageSuccessRate = 1.0,
        averageOutputTokensPerSecond = 5.0,
        inputCacheHitRate = null
    )

    private val failure = RepositoryError.OtherError("no connection")

    @BeforeTest
    fun setup() {
        sessionRepository = mockk()
        state = mockk(relaxed = true)
        activeSessionId = MutableStateFlow(sessionId)
        every { state.activeSessionId } returns activeSessionId
    }

    /** Builds the use case on a scope that only runs on an explicit scheduler advance. */
    private fun createUseCase(scope: TestScope): ShowThreadStatsUseCase =
        ShowThreadStatsUseCase(sessionRepository, state, CoroutineScope(StandardTestDispatcher(scope.testScheduler)))

    @Test
    fun `show publishes loading and then the fetched statistics`() = runTest {
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit
        // Hold the fetch open so the loading state is observable before the statistics arrive.
        val gate = CompletableDeferred<Unit>()
        coEvery { sessionRepository.getThreadStats(sessionId) } coAnswers {
            gate.await()
            Either.Right(stats)
        }

        createUseCase(this).show()
        runCurrent()

        val dialog = assertIs<ChatAreaDialogState.ThreadStatsDetails>(dialogSlot.captured)
        assertIs<DataState.Loading>(dialog.stats.value)

        gate.complete(Unit)
        advanceUntilIdle()

        val success = assertIs<DataState.Success<ThreadStats>>(dialog.stats.value)
        assertEquals(stats, success.data)
    }

    @Test
    fun `show publishes the error when the fetch fails`() = runTest {
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit
        coEvery { sessionRepository.getThreadStats(sessionId) } returns Either.Left(failure)

        createUseCase(this).show()
        advanceUntilIdle()

        val dialog = assertIs<ChatAreaDialogState.ThreadStatsDetails>(dialogSlot.captured)
        val error = assertIs<DataState.Error<RepositoryError>>(dialog.stats.value)
        assertEquals(failure, error.error)
    }

    @Test
    fun `retry fetches again and publishes the statistics`() = runTest {
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit
        coEvery { sessionRepository.getThreadStats(sessionId) } returnsMany listOf(
            Either.Left(failure),
            Either.Right(stats)
        )

        val useCase = createUseCase(this)
        useCase.show()
        advanceUntilIdle()
        val dialog = assertIs<ChatAreaDialogState.ThreadStatsDetails>(dialogSlot.captured)
        assertIs<DataState.Error<RepositoryError>>(dialog.stats.value)

        dialog.onReload()
        advanceUntilIdle()

        val success = assertIs<DataState.Success<ThreadStats>>(dialog.stats.value)
        assertEquals(stats, success.data)
        coVerify(exactly = 2) { sessionRepository.getThreadStats(sessionId) }
    }

    @Test
    fun `reopening the dialog does not render the previous statistics`() = runTest {
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit
        val gate = CompletableDeferred<Unit>()
        coEvery { sessionRepository.getThreadStats(sessionId) } coAnswers {
            gate.await()
            Either.Right(stats)
        }

        val useCase = createUseCase(this)
        useCase.show()
        gate.complete(Unit)
        advanceUntilIdle()
        assertIs<DataState.Success<ThreadStats>>(
            assertIs<ChatAreaDialogState.ThreadStatsDetails>(dialogSlot.captured).stats.value
        )

        useCase.show()

        val reopened = assertIs<ChatAreaDialogState.ThreadStatsDetails>(dialogSlot.captured)
        assertIs<DataState.Loading>(reopened.stats.value)
        advanceUntilIdle()
        assertIs<DataState.Success<ThreadStats>>(reopened.stats.value)
    }

    @Test
    fun `a superseded load cannot overwrite the newer statistics`() = runTest {
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit
        val staleStats = stats.copy(totalMessages = 99)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { sessionRepository.getThreadStats(sessionId) } coAnswers {
            calls += 1
            if (calls == 1) {
                gate.await()
                Either.Right(staleStats)
            } else {
                Either.Right(stats)
            }
        }

        val useCase = createUseCase(this)
        useCase.show()
        // The first fetch is held open, so the second open supersedes a request that is still running.
        runCurrent()
        useCase.show()
        advanceUntilIdle()

        val dialog = assertIs<ChatAreaDialogState.ThreadStatsDetails>(dialogSlot.captured)
        assertEquals(stats, assertIs<DataState.Success<ThreadStats>>(dialog.stats.value).data)

        // The abandoned request completing late must not replace the newer result.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(stats, assertIs<DataState.Success<ThreadStats>>(dialog.stats.value).data)
    }

    @Test
    fun `show does nothing without an active session`() = runTest {
        activeSessionId.value = null

        createUseCase(this).show()
        advanceUntilIdle()

        verify(exactly = 0) { state.setDialogState(any()) }
        coVerify(exactly = 0) { sessionRepository.getThreadStats(any()) }
    }

    @Test
    fun `dismissing the dialog closes it`() = runTest {
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit
        coEvery { sessionRepository.getThreadStats(sessionId) } returns Either.Right(stats)

        createUseCase(this).show()
        advanceUntilIdle()

        val dialog = assertIs<ChatAreaDialogState.ThreadStatsDetails>(dialogSlot.captured)
        dialog.onDismiss()

        verify(exactly = 1) { state.cancelDialog() }
    }
}
