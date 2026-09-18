package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.streamInterruptedCompletionState
import eu.torvian.chatbot.server.service.llm.unexpectedFailureCompletionState
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Covers the failure endings of a streaming turn: provider errors, missing terminal chunks and unexpected throws. */
class DefaultConversationTurnOrchestratorStreamingFailureEndingTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

    /**
     * Verifies the streaming failure path: the partial text and the failure state are persisted, the failure
     * is only reported through the message bubble plus the unchanged transient notification, and the turn closes
     * with exactly one terminal frame after the finalized message.
     */
    @Test
    fun `processStreamingTurn persists failure state and partial content when the stream reports an error`() = runTest {
        val userMessage = streamingUserMessage(511L, "Fail me")
        val placeholder = streamingPlaceholder(512L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val authenticationError = LLMCompletionError.AuthenticationError("invalid api key")
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Partial answer").right(),
            LLMStreamChunk.Error(authenticationError).right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Fail me")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.AUTHENTICATION_FAILED,
                    message = "The provider rejected the API key or credentials."
                )
            )
        }
        // Documented failure ordering: the transient error notification, the finalized (failed) message, and
        // then exactly one terminal frame.
        assertEquals(6, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertIs<ConversationTurnEvent.AssistantMessageStarted>(events[1])
        assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[2])
        val errorEvent = assertIs<ConversationTurnEvent.ExternalServiceError>(events[3])
        assertEquals(authenticationError, errorEvent.llmError)
        val finished = assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[4])
        assertEquals("Partial answer", finished.assistantMessage.content)
        assertFalse(finished.assistantMessage.isComplete)
        assertEquals(AssistantMessageIncompleteCause.FAILED, finished.assistantMessage.incompleteCause)
        assertEquals(AssistantMessageErrorCode.AUTHENTICATION_FAILED, finished.assistantMessage.errorCode)
        assertEquals(ConversationTurnEvent.TurnCompleted, events[5])
    }

    /**
     * Verifies that a provider stream which ends without any terminal chunk is recorded as a stream
     * interruption with the text received so far, and that the turn still closes.
     */
    @Test
    fun `processStreamingTurn records a stream ended without terminal chunk as interrupted`() = runTest {
        val userMessage = streamingUserMessage(521L, "Half a stream")
        val placeholder = streamingPlaceholder(522L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        // No `Done`, no error chunk and no cancellation: the stream simply ends early.
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Half an answer").right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Half a stream")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Half an answer",
                streamInterruptedCompletionState()
            )
        }
        assertEquals(5, events.size)
        val finished = assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[3])
        assertEquals(AssistantMessageErrorCode.STREAM_INTERRUPTED, finished.assistantMessage.errorCode)
        assertEquals(ConversationTurnEvent.TurnCompleted, events[4])
        // No new transient error frame is introduced for this ending.
        assertTrue(events.none { it is ConversationTurnEvent.ExternalServiceError })
    }

    /**
     * Verifies the unexpected-failure path: a non-cancellation exception leaves the partial text persisted as
     * an unexpected failure, the finalized message is still delivered through the live stream (its socket is
     * alive), and the exception is rethrown so the chat service can still report a turn-level error.
     */
    @Test
    fun `processStreamingTurn records an unexpected failure and rethrows when the stream throws`() = runTest {
        val userMessage = streamingUserMessage(551L, "Boom")
        val placeholder = streamingPlaceholder(552L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(LLMStreamChunk.ContentChunk("Partial answer").right())
            throw IllegalStateException("provider flow exploded")
        }

        val events = mutableListOf<ConversationTurnEvent>()
        val thrown = runCatching {
            orchestrator.processStreamingTurn(streamingTurnRequest("Boom")).collect { events.add(it) }
        }.exceptionOrNull()

        assertIs<IllegalStateException>(thrown)
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                unexpectedFailureCompletionState()
            )
        }
        // The turn is closed by ChatServiceImpl (UnexpectedError + StreamCompleted) after the rethrow, so the
        // orchestrator must not emit a terminal frame here.
        assertTrue(events.none { it == ConversationTurnEvent.TurnCompleted })
        // The durable write above is not enough: the unexpected failure has a live socket, so the finalized
        // message must also reach the client through the stream instead of being re-read by a later request.
        assertTrue(events.any { it is ConversationTurnEvent.AssistantMessageFinished })
    }

    /**
     * Verifies the exactly-once rule: an exception raised after the message was already finalized must not
     * re-flag the completed answer as a failure, even though the unexpected-failure handler runs.
     */
    @Test
    fun `processStreamingTurn keeps a finalized message completed when the stream throws afterwards`() = runTest {
        val userMessage = streamingUserMessage(561L, "Late boom")
        val placeholder = streamingPlaceholder(562L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(LLMStreamChunk.ContentChunk("Complete answer", finishReason = "stop").right())
            emit(LLMStreamChunk.Done.right())
            // The provider flow fails only after the terminal chunk was already delivered and persisted.
            throw IllegalStateException("late provider failure")
        }

        val thrown = runCatching {
            orchestrator.processStreamingTurn(streamingTurnRequest("Late boom")).toList()
        }.exceptionOrNull()

        assertIs<IllegalStateException>(thrown)
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Complete answer",
                AssistantMessageCompletionState.Completed
            )
        }
        // The finalizer is a no-op once the message reached a terminal state, so nothing is re-flagged.
        coVerify(exactly = 1) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
    }
}
