package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.reasoningOutputLimitExceededCompletionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the plaintext reasoning a streaming step renders and persists.
 *
 * The verified behavior is the split between the two consumers of the same text: the live reasoning deltas reach
 * the client in stream order, while the accumulated text is stored as one derived reasoning item next to the
 * completed items, so a reload can render what the stream displayed.
 */
class DefaultConversationTurnOrchestratorStreamingReasoningTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

    /** Raw completed item as a provider emits it, including output-only fields the persistence layer strips. */
    private val completedReasoningItem: JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_stream")
        put("status", "completed")
        put("format", "unknown")
    }

    /** The same item after sanitizing, which is the shape that enters persistence and replay. */
    private val sanitizedCompletedReasoningItem: JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_stream")
    }

    /**
     * Builds the item the runner is expected to append for streamed text.
     *
     * @param text Accumulated plaintext reasoning of the step.
     * @return Reasoning item holding one `reasoning_text` part with [text].
     */
    private fun streamedReasoningItem(text: String): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "reasoning_text")
                        put("text", text)
                    }
                )
            }
        )
    }

    /**
     * Verifies that streamed reasoning text reaches the client as ordered deltas of the placeholder message and
     * that the accumulated text is persisted next to the completed reasoning item.
     */
    @Test
    fun `processStreamingTurn emits ordered reasoning deltas and persists completed plus streamed reasoning`() = runTest {
        val userMessage = streamingUserMessage(601L, "Think it through")
        val placeholder = streamingPlaceholder(602L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery {
            conversationTurnPersistence.updateAssistantMessageReasoning(any(), any())
        } returns placeholder
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ReasoningTextChunk(outputIndex = 0, contentIndex = 0, delta = "thinking ").right(),
            LLMStreamChunk.ReasoningTextChunk(outputIndex = 0, contentIndex = 0, delta = "hard").right(),
            LLMStreamChunk.ReasoningDone(reasoningItem = completedReasoningItem).right(),
            LLMStreamChunk.ContentChunk("Answer", finishReason = "stop").right(),
            LLMStreamChunk.Done.right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Think it through")).toList()

        val reasoningDeltas = events.filterIsInstance<ConversationTurnEvent.AssistantMessageReasoningDelta>()
        assertEquals(listOf("thinking ", "hard"), reasoningDeltas.map { it.deltaContent })
        assertTrue(reasoningDeltas.all { it.messageId == placeholder.id })
        // The deltas arrive before the content, in the order the provider produced them.
        assertEquals("thinking hard", reasoningDeltas.joinToString("") { it.deltaContent })
        assertEquals(
            listOf(
                ConversationTurnEvent.AssistantMessageReasoningDelta(placeholder.id, "thinking "),
                ConversationTurnEvent.AssistantMessageReasoningDelta(placeholder.id, "hard")
            ),
            reasoningDeltas
        )
        assertTrue(events.indexOfFirst { it is ConversationTurnEvent.AssistantMessageReasoningDelta } <
            events.indexOfFirst { it is ConversationTurnEvent.AssistantMessageDelta })

        // The persisted list carries the completed item (sanitized) plus exactly one derived streamed-text item.
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageReasoning(
                placeholder.id,
                listOf(sanitizedCompletedReasoningItem, streamedReasoningItem("thinking hard"))
            )
        }
        // Capability detection must only ever see the completed items, never the derived display payload.
        coVerify(exactly = 1) {
            reasoningCapabilityRecorder.record(testModel, listOf(completedReasoningItem))
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
        assertEquals("Answer", finished.assistantMessage.content)
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies that a completed item which already carries plaintext reasoning is not doubled up by the streamed
     * text: the same chain of thought must not be persisted (and later replayed) twice.
     */
    @Test
    fun `processStreamingTurn does not append streamed text when a completed item carries plaintext content`() = runTest {
        val userMessage = streamingUserMessage(611L, "Already reasoned")
        val placeholder = streamingPlaceholder(612L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val itemWithContent = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_with_content")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "reasoning_text")
                            put("text", "the whole chain of thought")
                        }
                    )
                }
            )
        }
        coEvery {
            conversationTurnPersistence.updateAssistantMessageReasoning(any(), any())
        } returns placeholder
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ReasoningTextChunk(outputIndex = 0, contentIndex = 0, delta = "the whole ").right(),
            LLMStreamChunk.ReasoningTextChunk(outputIndex = 0, contentIndex = 0, delta = "chain of thought").right(),
            LLMStreamChunk.ReasoningDone(reasoningItem = itemWithContent).right(),
            LLMStreamChunk.ContentChunk("Answer", finishReason = "stop").right(),
            LLMStreamChunk.Done.right()
        )

        orchestrator.processStreamingTurn(streamingTurnRequest("Already reasoned")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageReasoning(
                placeholder.id,
                listOf(itemWithContent)
            )
        }
    }

    /**
     * Verifies that reasoning a step never streamed is not invented as an empty item: the completed items alone
     * are persisted, and a step with no reasoning at all performs no reasoning write.
     */
    @Test
    fun `processStreamingTurn persists only the completed items when no reasoning text was streamed`() = runTest {
        val userMessage = streamingUserMessage(621L, "Encrypted reasoning")
        val placeholder = streamingPlaceholder(622L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery {
            conversationTurnPersistence.updateAssistantMessageReasoning(any(), any())
        } returns placeholder
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ReasoningDone(reasoningItem = completedReasoningItem).right(),
            LLMStreamChunk.ContentChunk("Answer", finishReason = "stop").right(),
            LLMStreamChunk.Done.right()
        )

        orchestrator.processStreamingTurn(streamingTurnRequest("Encrypted reasoning")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageReasoning(
                placeholder.id,
                listOf(sanitizedCompletedReasoningItem)
            )
        }
    }

    /**
     * Verifies that a step whose provider emitted no reasoning at all leaves the reasoning column untouched.
     */
    @Test
    fun `processStreamingTurn writes no reasoning when the step produced none`() = runTest {
        val userMessage = streamingUserMessage(631L, "Plain answer")
        val placeholder = streamingPlaceholder(632L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Answer", finishReason = "stop").right(),
            LLMStreamChunk.Done.right()
        )

        orchestrator.processStreamingTurn(streamingTurnRequest("Plain answer")).toList()

        coVerify(exactly = 0) { conversationTurnPersistence.updateAssistantMessageReasoning(any(), any()) }
    }

    /**
     * Verifies that reaching the reasoning character cap cuts the accumulated text, fails the message with the
     * dedicated code and ends the turn before any requested tool call is persisted or executed.
     */
    @Test
    fun `processStreamingTurn flags reasoning over the cap and never runs its tool calls`() = runTest {
        val toolDefinition = LocalMCPToolDefinition(
            id = 8L,
            name = "search",
            description = "Searches docs",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            outputSchema = null,
            isEnabled = true,
            createdAt = baseInstant,
            updatedAt = baseInstant,
            serverId = 1L,
            mcpToolName = "search"
        )
        val userMessage = streamingUserMessage(641L, "Think forever")
        val placeholder = streamingPlaceholder(642L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery {
            conversationTurnPersistence.updateAssistantMessageReasoning(any(), any())
        } returns placeholder
        // Derived from the constant rather than a literal, so tuning the cap keeps the test meaningful.
        val oversizedReasoning = "r".repeat(ConversationTurnLimits.MAX_REASONING_TEXT_CHARS + 1)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ReasoningTextChunk(outputIndex = 0, contentIndex = 0, delta = oversizedReasoning).right(),
            LLMStreamChunk.ToolCallChunk(
                index = 0,
                id = "call_after_cap",
                name = "search",
                argumentsDelta = "{\"query\":\"docs\"}"
            ).right(),
            LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
            LLMStreamChunk.Done.right()
        )

        val events = orchestrator.processStreamingTurn(
            streamingTurnRequest("Think forever", tools = listOf(toolDefinition))
        ).toList()

        val cappedReasoning = oversizedReasoning.take(ConversationTurnLimits.MAX_REASONING_TEXT_CHARS)
        // Only the fraction within the cap is rendered and persisted.
        val streamedDelta = assertIs<ConversationTurnEvent.AssistantMessageReasoningDelta>(
            events.single { it is ConversationTurnEvent.AssistantMessageReasoningDelta }
        )
        assertEquals(cappedReasoning, streamedDelta.deltaContent)
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageReasoning(
                placeholder.id,
                listOf(streamedReasoningItem(cappedReasoning))
            )
        }
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "",
                reasoningOutputLimitExceededCompletionState(ConversationTurnLimits.MAX_REASONING_TEXT_CHARS), usageStats = any())
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
        assertFalse(finished.assistantMessage.isComplete)
        assertEquals(AssistantMessageErrorCode.REASONING_OUTPUT_LIMIT_EXCEEDED, finished.assistantMessage.errorCode)
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
        // A response whose reasoning had to be cut is untrustworthy input for side-effecting tools.
        assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
        coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
        verify(exactly = 0) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
    }

    /**
     * Verifies that an abnormal ending keeps the partial reasoning: the reasoning write is part of the same
     * exactly-once finalization that records the failure state.
     */
    @Test
    fun `processStreamingTurn persists partial reasoning when the stream fails`() = runTest {
        val userMessage = streamingUserMessage(651L, "Fails midway")
        val placeholder = streamingPlaceholder(652L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery {
            conversationTurnPersistence.updateAssistantMessageReasoning(any(), any())
        } returns placeholder
        val failure = LLMCompletionError.NetworkError(message = "socket closed", cause = null)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ReasoningTextChunk(outputIndex = 0, contentIndex = 0, delta = "half a thought").right(),
            LLMStreamChunk.Error(failure).right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Fails midway")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageReasoning(
                placeholder.id,
                listOf(streamedReasoningItem("half a thought"))
            )
        }
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
                    message = "The provider could not be reached because of a network error."
                ), usageStats = any())
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
        assertFalse(finished.assistantMessage.isComplete)
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }
}
