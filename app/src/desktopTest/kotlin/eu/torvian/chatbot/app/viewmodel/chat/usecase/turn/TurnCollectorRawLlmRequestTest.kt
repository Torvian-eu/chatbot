package eu.torvian.chatbot.app.viewmodel.chat.usecase.turn

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.service.agent.OperatorToolExecutor
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SendMessageUseCase
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Verifies that both turn collectors store the captured provider request in chat state, so the request
 * viewer of either chat surface renders the latest prepared request of the turn.
 */
class TurnCollectorRawLlmRequestTest {

    private val sessionId = 5L
    private val request = ProcessNewMessageRequest(content = "hello")

    private val payload = RawLlmRequestPayload(
        method = "POST",
        url = "https://api.example.com/v1/chat/completions",
        headers = mapOf("Content-Type" to "application/json"),
        body = """{"model":"gpt-4"}"""
    )

    private val sessionRepository = mockk<SessionRepository>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val operatorToolExecutor = mockk<OperatorToolExecutor>(relaxed = true)
    private val state = mockk<ChatState>(relaxed = true)

    private fun tracking() = SendMessageUseCase.TurnStatusTracking(sessionId)

    @Test
    fun `the streaming capture is written into chat state`() = runTest {
        val events: Flow<Either<RepositoryError, ChatStreamEvent>> =
            flowOf(ChatStreamEvent.RawLlmRequest(payload).right())
        every { sessionRepository.processNewMessageStreaming(sessionId, any()) } returns events

        StreamingTurnCollector(
            sessionRepository = sessionRepository,
            notificationService = notificationService,
            operatorToolExecutor = operatorToolExecutor,
            state = state,
            clientEventFlow = MutableSharedFlow(),
            handleToolCallApprovalRequested = { false },
            noteApprovalDeferred = { _, _ -> }
        ).handleStreamingMessage(sessionId, request, tracking())

        verify(exactly = 1) { state.setRawLlmRequest(payload) }
    }

    @Test
    fun `the non-streaming capture is written into chat state`() = runTest {
        val events: Flow<Either<RepositoryError, ChatEvent>> = flowOf(ChatEvent.RawLlmRequest(payload).right())
        every { sessionRepository.processNewMessage(sessionId, any()) } returns events

        NonStreamingTurnCollector(
            sessionRepository = sessionRepository,
            notificationService = notificationService,
            operatorToolExecutor = operatorToolExecutor,
            state = state,
            clientEventFlow = MutableSharedFlow(),
            handleToolCallApprovalRequested = { false },
            noteApprovalDeferred = { _, _ -> }
        ).handleNonStreamingMessage(sessionId, request, tracking())

        verify(exactly = 1) { state.setRawLlmRequest(payload) }
    }

    @Test
    fun `an unrelated event leaves the capture untouched`() = runTest {
        val events: Flow<Either<RepositoryError, ChatEvent>> = flowOf(ChatEvent.StreamCompleted.right())
        every { sessionRepository.processNewMessage(sessionId, any()) } returns events

        NonStreamingTurnCollector(
            sessionRepository = sessionRepository,
            notificationService = notificationService,
            operatorToolExecutor = operatorToolExecutor,
            state = state,
            clientEventFlow = MutableSharedFlow(),
            handleToolCallApprovalRequested = { false },
            noteApprovalDeferred = { _, _ -> }
        ).handleNonStreamingMessage(sessionId, request, tracking())

        verify(exactly = 0) { state.setRawLlmRequest(any()) }
    }
}
