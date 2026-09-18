package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Covers user-initiated stops of a streaming turn and the interrupted state persisted for their partial content. */
class DefaultConversationTurnOrchestratorStreamingStopTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

    /**
     * Verifies the drain-completed stop sub-path: the user stops the turn while the provider stream is still
     * open, the stream ends inside the drain window, and the finalizer after the collect records the partial
     * content together with the interruption cause.
     */
    @Test
    fun `processStreamingTurn persists interrupted state and partial content when the user stops the stream`() =
        runTest {
            val turnControlSignal = TurnControlSignal()
            val userMessage = streamingUserMessage(501L, "Stop me")
            val placeholder = streamingPlaceholder(502L, userMessage.id)
            stubStreamingTurnStart(userMessage, placeholder)
            // The stop is observed between two chunks, so every later chunk (including the terminal one) is
            // skipped and the collect returns without a provider completion.
            coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
                emit(LLMStreamChunk.ContentChunk("Partial answer").right())
                turnControlSignal.cancel()
                emit(LLMStreamChunk.ContentChunk(" never delivered").right())
                emit(LLMStreamChunk.Done.right())
            }

            val events = orchestrator.processStreamingTurn(streamingTurnRequest("Stop me", turnControlSignal)).toList()

            coVerify(exactly = 1) {
                conversationTurnPersistence.updateAssistantMessageContent(
                    placeholder.id,
                    "Partial answer",
                    AssistantMessageCompletionState.InterruptedByUser
                )
            }
            val finished = assertIs<ConversationTurnEvent.AssistantMessageFinished>(events.last())
            assertEquals("Partial answer", finished.assistantMessage.content)
            assertFalse(finished.assistantMessage.isComplete)
            assertEquals(
                AssistantMessageIncompleteCause.INTERRUPTED_BY_USER,
                finished.assistantMessage.incompleteCause
            )
            assertNull(finished.assistantMessage.errorCode)
            assertNull(finished.assistantMessage.errorMessage)
            // A stopped turn deliberately ends without a terminal frame (unchanged behavior).
            assertTrue(events.none { it == ConversationTurnEvent.TurnCompleted })
        }

    /**
     * Verifies a stop that arrives before any content is persisted with empty content: the state must be
     * recorded even though there is no partial text to show (regression against an emptiness guard).
     */
    @Test
    fun `processStreamingTurn persists the interrupted state even when no content was received`() = runTest {
        val turnControlSignal = TurnControlSignal()
        val userMessage = streamingUserMessage(503L, "Stop immediately")
        val placeholder = streamingPlaceholder(504L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            turnControlSignal.cancel()
            emit(LLMStreamChunk.ContentChunk("never shown").right())
            emit(LLMStreamChunk.Done.right())
        }

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Stop immediately", turnControlSignal)).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "",
                AssistantMessageCompletionState.InterruptedByUser
            )
        }
        val finished = assertIs<ConversationTurnEvent.AssistantMessageFinished>(events.last())
        assertEquals("", finished.assistantMessage.content)
        assertEquals(
            AssistantMessageIncompleteCause.INTERRUPTED_BY_USER,
            finished.assistantMessage.incompleteCause
        )
    }

    /**
     * Verifies the hard-teardown stop sub-path: the socket is torn down while the provider stream is still
     * open, so the terminal state has to be written from the cancellation handler under `NonCancellable` even
     * though the collecting coroutine is already cancelled. Delivering the terminal event is best-effort there
     * (the collector is gone), which is why only the persisted state is asserted.
     */
    @Test
    fun `processStreamingTurn persists interrupted state on hard teardown of a stopped stream`() = runTest {
        val streamGate = CompletableDeferred<Unit>()
        val userMessage = streamingUserMessage(505L, "Tear down")
        val placeholder = streamingPlaceholder(506L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(LLMStreamChunk.ContentChunk("Partial answer").right())
            // The provider stream stays open until the socket is torn down.
            streamGate.await()
            emit(LLMStreamChunk.Done.right())
        }

        val events = mutableListOf<ConversationTurnEvent>()
        val turnJob = launch {
            orchestrator.processStreamingTurn(streamingTurnRequest("Tear down"))
                .collect { events.add(it) }
        }
        // Let the turn run up to the open provider stream, then tear the socket down (virtual-time advance so
        // the launched turn is fully started before it is cancelled).
        delay(1.milliseconds)
        turnJob.cancel()
        turnJob.join()

        assertTrue(turnJob.isCancelled)
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                AssistantMessageCompletionState.InterruptedByUser
            )
        }
        assertTrue(events.none { it == ConversationTurnEvent.TurnCompleted })
    }
}
