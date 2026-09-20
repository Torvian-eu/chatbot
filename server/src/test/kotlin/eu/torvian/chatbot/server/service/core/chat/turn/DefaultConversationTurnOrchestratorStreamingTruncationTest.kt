package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.outputLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.strategy.OllamaChatStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Covers truncated streaming responses and the tool calls they must never persist or execute. */
class DefaultConversationTurnOrchestratorStreamingTruncationTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

    /**
     * Verifies that hitting the character cap is recorded as a failure without an in-content notice, and that the
     * turn terminates before any parsed tool call is persisted or executed.
     */
    @Test
    fun `processStreamingTurn flags a truncated response and never persists or executes its tool calls`() = runTest {
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
        val userMessage = streamingUserMessage(531L, "Too long")
        val placeholder = streamingPlaceholder(532L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val oversizedContent = "a".repeat(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS + 1)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk(oversizedContent).right(),
            LLMStreamChunk.ToolCallChunk(
                index = 0,
                id = "call_truncated",
                name = "search",
                argumentsDelta = "{\"query\":\"docs\"}"
            ).right(),
            LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
            LLMStreamChunk.Done.right()
        )

        val events = orchestrator.processStreamingTurn(
            streamingTurnRequest("Too long", tools = listOf(toolDefinition))
        ).toList()

        val truncatedContent = oversizedContent.take(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                truncatedContent,
                outputLimitExceededCompletionState(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
            )
        }
        // The former in-content truncation notice is gone: the reason lives in the message state now.
        assertFalse(truncatedContent.contains("[Output truncated"))
        assertEquals(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS, truncatedContent.length)
        // User saved, placeholder, the truncated delta, the live tool-call delta, the finalized message and
        // the single terminal frame.
        assertEquals(6, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertIs<ConversationTurnEvent.AssistantMessageStarted>(events[1])
        val truncatedDelta = assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[2])
        assertEquals(truncatedContent, truncatedDelta.deltaContent)
        assertIs<ConversationTurnEvent.ToolCallDelta>(events[3])
        val finished = assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[4])
        assertEquals(truncatedContent, finished.assistantMessage.content)
        assertEquals(AssistantMessageErrorCode.OUTPUT_LIMIT_EXCEEDED, finished.assistantMessage.errorCode)
        assertEquals(ConversationTurnEvent.TurnCompleted, events[5])
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
        // Nothing parsed from a truncated response reaches persistence or execution.
        assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
        coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
        verify(exactly = 0) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
    }

    /**
     * Verifies that a tool-calling iteration that completed normally stays completed when the iteration after it
     * is truncated, and that only the truncated message is flagged.
     */
    @Test
    fun `processStreamingTurn keeps an earlier completed iteration when a later iteration is truncated`() = runTest {
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
        val userMessage = streamingUserMessage(541L, "Two steps")
        val firstPlaceholder = streamingPlaceholder(542L, userMessage.id)
        val secondPlaceholder = streamingPlaceholder(544L, firstPlaceholder.id)
        stubStreamingTurnStart(userMessage, firstPlaceholder)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                firstPlaceholder.id,
                testModel,
                testSettings.copy(stream = true),
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
            )
        } returns PersistedAssistantMessage(secondPlaceholder, firstPlaceholder)
        val pendingToolCall = ToolCall(
            id = 543L,
            messageId = firstPlaceholder.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = "call_search",
            input = "{\"query\":\"docs\"}",
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )
        val oversizedContent = "b".repeat(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS + 5)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            // Iteration 1: a completed tool-calling response.
            flowOf(
                LLMStreamChunk.ToolCallChunk(
                    index = 0,
                    id = "call_search",
                    name = "search",
                    argumentsDelta = "{\"query\":\"docs\"}"
                ).right(),
                LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
                LLMStreamChunk.Done.right()
            ),
            // Iteration 2: the follow-up answer ran into the character cap.
            flowOf(
                LLMStreamChunk.ContentChunk(oversizedContent, finishReason = "stop").right(),
                LLMStreamChunk.Done.right()
            )
        )
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(
                firstPlaceholder.id,
                any(),
                listOf(toolDefinition)
            )
        } returns listOf(pendingToolCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        } returns flowOf(
            ToolCallExecutionEvent.ToolCallCompleted(
                pendingToolCall.copy(
                    output = "{\"results\":[]}",
                    status = ToolCallStatus.SUCCESS,
                    durationMs = 5L
                )
            )
        )

        val events = orchestrator.processStreamingTurn(
            streamingTurnRequest("Two steps", tools = listOf(toolDefinition))
        ).toList()

        // The iteration that completed normally is marked completed, so only the truncated message is flagged.
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                firstPlaceholder.id,
                "",
                AssistantMessageCompletionState.Completed
            )
        }
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                secondPlaceholder.id,
                oversizedContent.take(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS),
                outputLimitExceededCompletionState(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
            )
        }
        // The completed iteration still persisted and executed its tool call before the truncation.
        coVerify(exactly = 1) { conversationTurnPersistence.persistPendingToolCalls(firstPlaceholder.id, any(), any()) }
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
    }

    /**
     * Verifies the Ollama path end to end: the dialect turns a `length` terminal reason into a failure the
     * collector records as the single ending, so the message keeps the partial answer, the tool call requested
     * before the cut is neither persisted nor executed, and the turn closes once with one transient frame.
     */
    @Test
    fun `processStreamingTurn persists an Ollama length truncation with its partial content and runs no tool`() =
        runTest {
            val toolDefinition = LocalMCPToolDefinition(
                id = 11L,
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
            val userMessage = streamingUserMessage(551L, "Ollama truncated")
            val placeholder = streamingPlaceholder(552L, userMessage.id)
            stubStreamingTurnStart(userMessage, placeholder)
            // The stream is the real Ollama dialect output: a partial answer plus a tool call, then the terminal
            // chunk declaring that the server stopped the model at its output limit.
            val ollamaStream = listOf(
                "{\"model\":\"llama3.2\",\"created_at\":\"2023-12-07T09:32:18Z\"," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"Partial answer\"," +
                    "\"tool_calls\":[{\"function\":{\"name\":\"search\",\"arguments\":" +
                    "{\"query\":\"docs\"},\"index\":0}}]},\"done\":false}",
                "{\"model\":\"llama3.2\",\"created_at\":\"2023-12-07T09:32:18Z\"," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true," +
                    "\"done_reason\":\"length\",\"prompt_eval_count\":26,\"eval_count\":15}"
            )
            coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns
                    OllamaChatStrategy(Json { ignoreUnknownKeys = true; isLenient = true })
                        .processStreamingResponse(flowOf(*ollamaStream.toTypedArray()))

            val events = orchestrator.processStreamingTurn(
                streamingTurnRequest("Ollama truncated", tools = listOf(toolDefinition))
            ).toList()

            coVerify(exactly = 1) {
                conversationTurnPersistence.updateAssistantMessageContent(
                    placeholder.id,
                    "Partial answer",
                    AssistantMessageCompletionState.failed(
                        code = AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED,
                        message = "The provider stopped the response because the model reached its output limit."
                    )
                )
            }
            // One terminal write, one transient frame, one terminal frame.
            coVerify(exactly = 1) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
            val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
            assertEquals("Partial answer", finished.assistantMessage.content)
            assertFalse(finished.assistantMessage.isComplete)
            val errorFrame = assertIs<ConversationTurnEvent.ExternalServiceError>(
                events.single { it is ConversationTurnEvent.ExternalServiceError }
            )
            assertEquals(
                "length",
                assertIs<LLMCompletionError.ProviderFailureError>(errorFrame.llmError).providerCode
            )
            assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
            // The call the model asked for before the cut reaches neither persistence nor execution.
            assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
            coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
            verify(exactly = 0) {
                toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
            }
        }

    /**
     * Verifies that a streaming generation which hit the character cap *and* was declared cut short by the provider
     * is reported with the provider's classification while the persisted text stays cut at the cap.
     */
    @Test
    fun `processStreamingTurn prefers a declared ending over the character cap and still cuts the content`() = runTest {
        val userMessage = streamingUserMessage(561L, "Long and truncated")
        val placeholder = streamingPlaceholder(562L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val oversizedContent = "f".repeat(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS + 2)
        val declaredEnding = LLMCompletionError.ProviderFailureError(
            providerCode = "length",
            message = "The provider ended the response without completing it."
        )
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk(oversizedContent).right(),
            LLMStreamChunk.Error(declaredEnding).right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Long and truncated")).toList()

        val cappedContent = oversizedContent.take(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                cappedContent,
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED,
                    message = "The provider stopped the response because the model reached its output limit."
                )
            )
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
        assertEquals(cappedContent, finished.assistantMessage.content)
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies that an Ollama generation whose terminal reason declares no truncation stays a normal completion:
     * the message is finalized as completed and no transient error frame is produced.
     */
    @Test
    fun `processStreamingTurn completes an Ollama answer whose terminal reason declares no truncation`() = runTest {
        val userMessage = streamingUserMessage(553L, "Ollama stopped")
        val placeholder = streamingPlaceholder(554L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val ollamaStream = listOf(
            "{\"model\":\"llama3.2\",\"created_at\":\"2023-12-07T09:32:18Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"Complete answer\"},\"done\":false}",
            "{\"model\":\"llama3.2\",\"created_at\":\"2023-12-07T09:32:18Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}"
        )
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns
                OllamaChatStrategy(Json { ignoreUnknownKeys = true; isLenient = true })
                    .processStreamingResponse(flowOf(*ollamaStream.toTypedArray()))

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Ollama stopped")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Complete answer",
                AssistantMessageCompletionState.Completed
            )
        }
        coVerify(exactly = 1) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
        assertTrue(events.none { it is ConversationTurnEvent.ExternalServiceError })
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }
}
