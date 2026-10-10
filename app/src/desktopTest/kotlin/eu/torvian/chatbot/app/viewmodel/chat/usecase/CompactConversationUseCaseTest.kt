package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.api.core.CompactionCompletedPayload
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Tests the lifecycle and user feedback of [CompactConversationUseCase]: the progress state, the
 * per-outcome notification, the refusal of a concurrent start, and the cancellation path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompactConversationUseCaseTest {

    private val sessionRepository = mockk<SessionRepository>()
    private val state = mockk<ChatState>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)

    private val sessionId = 7L

    private val payload = CompactionCompletedPayload(
        chunkId = 42L,
        sessionId = sessionId,
        coveredMessageIds = listOf(1L, 2L),
        modelId = 1L,
        settingsId = 2L,
        providerId = 3L,
        modelName = "Model",
        settingsName = "Settings",
        providerName = "Provider",
        sourceTokenCount = 4_500L,
        resultTokenCount = 2_000L,
        summaryPreview = "A concise summary.",
        createdAt = 1_700_000_000_100L
    )

    /**
     * Builds the use case over a test scope.
     *
     * @param scope Scope owning the compaction job.
     * @return The use case under test.
     */
    private fun useCase(scope: CoroutineScope) = CompactConversationUseCase(
        sessionRepository = sessionRepository,
        state = state,
        notificationService = notificationService,
        scope = scope
    )

    @Test
    fun `a completed outcome shows the success notification and clears the indicator`() = runTest {
        every { sessionRepository.compactConversation(sessionId) } returns
            flowOf(CompactionEvent.Completed(payload).right())
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        useCase(scope).start(sessionId)

        verify { state.setTurnExecutionState(TurnExecutionState.COMPACTING) }
        coVerify {
            notificationService.genericSuccess(
                "Conversation compacted: 2 messages summarized (4500 → 2000 tokens)"
            )
        }
        verify { state.setTurnExecutionState(TurnExecutionState.IDLE) }
    }

    @Test
    fun `a skipped outcome shows the reason-specific notification`() = runTest {
        every { sessionRepository.compactConversation(sessionId) } returns
            flowOf(CompactionEvent.Skipped(CompactionSkipReason.ALREADY_COMPACTED).right())
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        useCase(scope).start(sessionId)

        coVerify { notificationService.genericWarning("Conversation already compacted") }
        verify { state.setTurnExecutionState(TurnExecutionState.IDLE) }
    }

    @Test
    fun `a summary that is not smaller is reported as an informational warning`() = runTest {
        every { sessionRepository.compactConversation(sessionId) } returns
            flowOf(CompactionEvent.Skipped(CompactionSkipReason.SUMMARY_NOT_SMALLER).right())
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        useCase(scope).start(sessionId)

        // A skip is informational: the user sees a warning, and no error surface is used.
        coVerify {
            notificationService.genericWarning(
                "Conversation not compacted: the summary was not smaller than the messages it replaces"
            )
        }
        coVerify(exactly = 0) { notificationService.apiError(any(), any<String>()) }
        verify { state.setTurnExecutionState(TurnExecutionState.IDLE) }
    }

    @Test
    fun `an error event is reported through the api error surface`() = runTest {
        val error = apiError(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED, "Compaction failed")
        every { sessionRepository.compactConversation(sessionId) } returns
            flowOf(CompactionEvent.ErrorOccurred(error).right())
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        useCase(scope).start(sessionId)

        coVerify { notificationService.apiError(error, "Failed to compact the conversation") }
        verify { state.setTurnExecutionState(TurnExecutionState.IDLE) }
    }

    @Test
    fun `a transport failure is reported through the repository error surface`() = runTest {
        val error = RepositoryError.OtherError("socket down")
        every { sessionRepository.compactConversation(sessionId) } returns
            flowOf<arrow.core.Either<RepositoryError, CompactionEvent>>(error.left())
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        useCase(scope).start(sessionId)

        coVerify { notificationService.repositoryError(error, "Failed to compact the conversation") }
        verify { state.setTurnExecutionState(TurnExecutionState.IDLE) }
    }

    @Test
    fun `the terminal marker alone leaves the outcome notifications untouched`() = runTest {
        every { sessionRepository.compactConversation(sessionId) } returns
            flowOf(CompactionEvent.StreamCompleted.right())
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        useCase(scope).start(sessionId)

        coVerify(exactly = 0) { notificationService.genericSuccess(any()) }
        coVerify(exactly = 0) { notificationService.genericWarning(any<String>()) }
        coVerify(exactly = 0) { notificationService.apiError(any(), any<String>()) }
        coVerify(exactly = 0) { notificationService.repositoryError(any(), any<String>()) }
        verify { state.setTurnExecutionState(TurnExecutionState.IDLE) }
    }

    @Test
    fun `a start while a compaction is running is ignored`() = runTest {
        every { sessionRepository.compactConversation(sessionId) } returns flow {
            emit(CompactionEvent.Completed(payload).right())
            awaitCancellation()
        }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val useCase = useCase(scope)

        useCase.start(sessionId)
        useCase.start(sessionId)

        verify(exactly = 1) { sessionRepository.compactConversation(sessionId) }
        useCase.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `cancelling the operation notifies the user and clears the indicator`() = runTest {
        every { sessionRepository.compactConversation(sessionId) } returns flow {
            emit(CompactionEvent.StreamCompleted.right())
            awaitCancellation()
        }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val useCase = useCase(scope)

        useCase.start(sessionId)
        verify { state.setTurnExecutionState(TurnExecutionState.COMPACTING) }

        useCase.cancel()
        advanceUntilIdle()

        // The cancellation notification must survive the cancelled job it is emitted from.
        coVerify { notificationService.genericWarning("Conversation compaction cancelled") }
        verify { state.setTurnExecutionState(TurnExecutionState.IDLE) }
    }
}
