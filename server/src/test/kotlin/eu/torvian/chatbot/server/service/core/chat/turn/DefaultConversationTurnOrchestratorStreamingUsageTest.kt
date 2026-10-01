package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers which token usage a streaming assistant step persists: the authoritative value of the provider's
 * terminal usage chunk, and nothing when the stream ended without one.
 */
class DefaultConversationTurnOrchestratorStreamingUsageTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

    /**
     * Verifies that the usage the provider reports for the whole generation reaches the finalization write of
     * the message and is delivered with the finishing event.
     */
    @Test
    fun `processStreamingTurn persists the usage reported immediately before the terminal chunk`() = runTest {
        val userMessage = streamingUserMessage(601L, "Count my tokens")
        val placeholder = streamingPlaceholder(602L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val reportedUsage = UsageStats(
            inputTokens = 120,
            outputTokens = 30,
            totalTokens = 150,
            reasoningTokens = 12,
            cachedTokens = 8,
            cacheWriteTokens = 4
        )
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Answer").right(),
            LLMStreamChunk.FinalUsageStats(reportedUsage).right(),
            LLMStreamChunk.Done.right()
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Answer",
                AssistantMessageCompletionState.Completed,
                usageStats = reportedUsage
            )
        } returns placeholder.copy(content = "Answer", isComplete = true, usageStats = reportedUsage)

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Count my tokens")).toList()

        // The usage is part of the terminal write: no extra statement, no extra event.
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Answer",
                AssistantMessageCompletionState.Completed,
                usageStats = reportedUsage
            )
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
        assertEquals(reportedUsage, finished.assistantMessage.usageStats)
    }

    /**
     * Verifies that an observed usage is discarded when the stream never reaches its terminal chunk, so an
     * interrupted generation records no usage at all.
     */
    @Test
    fun `processStreamingTurn discards an observed usage when the stream fails before completing`() = runTest {
        val userMessage = streamingUserMessage(611L, "Fail after usage")
        val placeholder = streamingPlaceholder(612L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Partial answer").right(),
            LLMStreamChunk.FinalUsageStats(UsageStats(10, 5, 15)).right(),
            LLMStreamChunk.Error(LLMCompletionError.AuthenticationError("invalid api key")).right()
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                any(),
                usageStats = null
            )
        } returns placeholder.copy(content = "Partial answer", isComplete = false)

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Fail after usage")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                any(),
                usageStats = null
            )
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
        assertNull(finished.assistantMessage.usageStats, "An abnormal ending records no usage")
    }

    /**
     * Verifies that a completion without a usage chunk records no usage instead of a zero-filled value.
     */
    @Test
    fun `processStreamingTurn records no usage when the provider reports none`() = runTest {
        val userMessage = streamingUserMessage(621L, "No usage")
        val placeholder = streamingPlaceholder(622L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Answer").right(),
            LLMStreamChunk.Done.right()
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Answer",
                AssistantMessageCompletionState.Completed,
                usageStats = null
            )
        } returns placeholder.copy(content = "Answer", isComplete = true)

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("No usage")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Answer",
                AssistantMessageCompletionState.Completed,
                usageStats = null
            )
        }
        assertNull(
            events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
                .assistantMessage.usageStats
        )
    }

    /**
     * Verifies that the usage observation chunk is neither an ending nor a reason to finalize the message
     * early: it is ignored by the collector and the stream keeps producing content afterwards.
     */
    @Test
    fun `processStreamingTurn keeps streaming after a usage observation chunk`() = runTest {
        val userMessage = streamingUserMessage(631L, "Observed")
        val placeholder = streamingPlaceholder(632L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.UsageChunk(UsageStats(3, 1, 4)).right(),
            LLMStreamChunk.ContentChunk("Answer").right(),
            LLMStreamChunk.Done.right()
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Answer",
                AssistantMessageCompletionState.Completed,
                usageStats = null
            )
        } returns placeholder.copy(content = "Answer", isComplete = true)

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Observed")).toList()

        val deltas = events.filterIsInstance<ConversationTurnEvent.AssistantMessageDelta>()
        assertEquals(listOf("Answer"), deltas.map { it.deltaContent })
        assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[events.size - 2])
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
    }

    /**
     * Verifies that a usage observation seen before the terminal chunk is not persisted in place of the
     * authoritative value: only the chunk the provider emitted last for the generation counts.
     */
    @Test
    fun `processStreamingTurn ignores the observation chunk when the authoritative usage is absent`() = runTest {
        val userMessage = streamingUserMessage(641L, "Only observation")
        val placeholder = streamingPlaceholder(642L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.UsageChunk(UsageStats(3, 1, 4)).right(),
            LLMStreamChunk.ContentChunk("Answer").right(),
            LLMStreamChunk.Done.right()
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Answer",
                AssistantMessageCompletionState.Completed,
                usageStats = null
            )
        } returns placeholder.copy(content = "Answer", isComplete = true)

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Only observation")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Answer",
                AssistantMessageCompletionState.Completed,
                usageStats = null
            )
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
        assertNull(finished.assistantMessage.usageStats, "An observation alone is never persisted")
    }
}
