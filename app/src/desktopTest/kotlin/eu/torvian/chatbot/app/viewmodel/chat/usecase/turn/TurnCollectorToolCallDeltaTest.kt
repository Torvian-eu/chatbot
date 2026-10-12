package eu.torvian.chatbot.app.viewmodel.chat.usecase.turn

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.repository.ConversationRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.agent.OperatorToolExecutor
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SendMessageUseCase
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Verifies that [StreamingTurnCollector] translates streaming tool-call events into the chat state's
 * construction record, so the indicator is armed by deltas and removed by every ending that makes it obsolete.
 */
class TurnCollectorToolCallDeltaTest {

    private val sessionId = 5L
    private val request = ProcessNewMessageRequest(content = "hello")
    private val messageId = 42L
    private val now = Instant.fromEpochSeconds(1_000L)

    private val conversationRepository = mockk<ConversationRepository>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val operatorToolExecutor = mockk<OperatorToolExecutor>(relaxed = true)
    private val state = mockk<ChatState>(relaxed = true)

    private fun tracking() = SendMessageUseCase.TurnStatusTracking(sessionId)

    /** Verifies every delta arms the record of its own message, so the freshness window can be measured. */
    @Test
    fun `a tool call delta records the arrival for its message`() = runTest {
        collect(flowOf(toolCallDelta().right(), toolCallDelta().right()))

        verify(exactly = 2) { state.noteToolCallDelta(messageId) }
        verify(exactly = 0) { state.forgetToolCallDeltas(any()) }
        verify(exactly = 0) { state.forgetAllToolCallDeltas() }
    }

    /** Verifies complete calls discard the construction record, so the badges replace the label at once. */
    @Test
    fun `received tool calls forget the record of their message`() = runTest {
        collect(flowOf(ChatStreamEvent.ToolCallsReceived(listOf(toolCall(1L), toolCall(2L))).right()))

        verify(exactly = 1) { state.forgetToolCallDeltas(setOf(messageId)) }
    }

    /** Verifies the end of a message discards its construction record. */
    @Test
    fun `an ended message forgets its record`() = runTest {
        collect(flowOf(ChatStreamEvent.AssistantMessageEnd(assistantMessage()).right()))

        verify(exactly = 1) { state.forgetToolCallDeltas(setOf(messageId)) }
    }

    /** Verifies an error discards every record, because no single message can be blamed for it. */
    @Test
    fun `an error forgets every record`() = runTest {
        collect(
            flowOf(
                ChatStreamEvent.ErrorOccurred(
                    ApiError(statusCode = 500, code = "internal-error", message = "boom")
                ).right()
            )
        )

        verify(exactly = 1) { state.forgetAllToolCallDeltas() }
        verify(exactly = 0) { state.forgetToolCallDeltas(any()) }
    }

    /** Verifies an event unrelated to tool calls leaves the record untouched. */
    @Test
    fun `an unrelated event records nothing`() = runTest {
        collect(flowOf(ChatStreamEvent.StreamCompleted.right()))

        verify(exactly = 0) { state.noteToolCallDelta(any()) }
        verify(exactly = 0) { state.forgetToolCallDeltas(any()) }
        verify(exactly = 0) { state.forgetAllToolCallDeltas() }
    }

    /**
     * Collects the supplied events through the streaming collector.
     *
     * @param events Events the repository stub is expected to emit.
     */
    private suspend fun collect(events: Flow<Either<RepositoryError, ChatStreamEvent>>) {
        every { conversationRepository.processNewMessageStreaming(sessionId, any()) } returns events

        StreamingTurnCollector(
            conversationRepository = conversationRepository,
            notificationService = notificationService,
            operatorToolExecutor = operatorToolExecutor,
            state = state,
            clientEventFlow = MutableSharedFlow(),
            handleToolCallApprovalRequested = { false },
            noteApprovalDeferred = { _, _ -> }
        ).handleStreamingMessage(sessionId, request, tracking())
    }

    /** Builds one tool-call delta of the tracked message; only its message ID is meaningful to the collector. */
    private fun toolCallDelta() = ChatStreamEvent.ToolCallDelta(
        messageId = messageId,
        index = null,
        id = null,
        name = "sample-tool",
        argumentsDelta = "{\"a\""
    )

    /** Builds one received tool call belonging to the tracked message. */
    private fun toolCall(id: Long) = ToolCall(
        id = id,
        messageId = messageId,
        toolDefinitionId = null,
        toolName = "sample-tool",
        status = ToolCallStatus.PENDING,
        executedAt = now
    )

    /** Builds the assistant message the stream belongs to. */
    private fun assistantMessage() = ChatMessage.AssistantMessage(
        id = messageId,
        sessionId = sessionId,
        content = "hi",
        createdAt = now,
        updatedAt = now,
        parentMessageId = null,
        modelId = 1L,
        settingsId = 2L
    )
}
