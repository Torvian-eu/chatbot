package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies the streaming path of [DefaultConversationTurnOrchestrator]'s shared assistant/tool loop,
 * including live deltas, reasoning persistence, and authoritative tool-call overrides.
 */
class DefaultConversationTurnOrchestratorStreamingTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies the streaming path still emits deltas before finalizing the assistant message.
     */
    @Test
    fun `processStreamingTurn emits streaming lifecycle and completion`() = runTest {
        val streamingSettings = testSettings.copy(stream = true)
        val userMessage = ChatMessage.UserMessage(
            id = 41L,
            sessionId = testSession.id,
            content = "Hello",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantStartedMessage = ChatMessage.AssistantMessage(
            id = 42L,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = streamingSettings.id
        )
        val assistantFinishedMessage = assistantStartedMessage.copy(content = "Hello there")

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Hello", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                userMessage.id,
                testModel,
                streamingSettings,
                agentRoleId = testRoleId,
                reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantStartedMessage, userMessage)
        coEvery {
            llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } returns flowOf(
            LLMStreamChunk.ContentChunk("Hello ").right(),
            LLMStreamChunk.ContentChunk("there", finishReason = "stop").right(),
            LLMStreamChunk.Done.right()
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(assistantStartedMessage.id, "Hello there")
        } returns assistantFinishedMessage

        val events = orchestrator.processStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, streamingSettings, "api-key"),
                content = "Hello",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        assertEquals(6, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertIs<ConversationTurnEvent.AssistantMessageStarted>(events[1])
        assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[2])
        assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[3])
        assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[4])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[5])

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(assistantStartedMessage.id, "Hello there")
        }
    }

    /**
     * Verifies that reasoning emitted during streaming is persisted on completion.
     */
    @Test
    fun `processStreamingTurn persists reasoning emitted during streaming`() = runTest {
        val reasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_s")
                put("encrypted_content", "opaque-stream")
            }
        )
        val userMessage = ChatMessage.UserMessage(
            id = 71L,
            sessionId = testSession.id,
            content = "Stream reason",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val reasoningModel = testModel.copy()
        val reasoningSettings = ResponsesModelSettings(
            id = 3L,
            modelId = reasoningModel.id,
            name = "Default Responses",
            stream = true,
            replayReasoning = true
        )
        val assistantStarted = ChatMessage.AssistantMessage(
            id = 72L,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = reasoningModel.id,
            settingsId = reasoningSettings.id
        )
        val assistantFinished = assistantStarted.copy(content = "Answer")

        coEvery {
            conversationTurnPersistence.saveUserMessage(
                testSession.id,
                "Stream reason",
                null,
                any()
            )
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id, "", userMessage.id, reasoningModel, reasoningSettings, agentRoleId = testRoleId, reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantStarted, userMessage)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ReasoningDone(reasoningItem = reasoningItems[0]).right(),
            // Plaintext reasoning deltas are render-only and must not be persisted.
            LLMStreamChunk.ReasoningTextChunk(outputIndex = 0, contentIndex = 0, delta = "The").right(),
            LLMStreamChunk.ContentChunk("Answer", finishReason = "stop").right(),
            LLMStreamChunk.Done.right()
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageReasoning(assistantStarted.id, reasoningItems)
        } returns assistantFinished
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(
                assistantStarted.id,
                "Answer"
            )
        } returns assistantFinished

        orchestrator.processStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, reasoningModel, reasoningSettings, "api-key"),
                content = "Stream reason",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageReasoning(assistantStarted.id, reasoningItems)
        }
        // The capability recorder must observe the model and its accumulated reasoning items so later
        // replays can adapt what is sent to this model.
        coVerify(exactly = 1) {
            reasoningCapabilityRecorder.record(reasoningModel, reasoningItems)
        }
    }

    /**
     * Verifies that a provider's authoritative ToolCallDone overrides the delta-accumulated arguments.
     */
    @Test
    fun `processStreamingTurn uses ToolCallDone as authoritative tool call arguments`() = runTest {
        val toolDefinition = LocalMCPToolDefinition(
            id = 8L,
            name = "getWeather",
            description = "Gets weather",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            outputSchema = null,
            isEnabled = true,
            createdAt = baseInstant,
            updatedAt = baseInstant,
            serverId = 1L,
            mcpToolName = "getWeather"
        )
        val userMessage = ChatMessage.UserMessage(
            id = 81L,
            sessionId = testSession.id,
            content = "Weather?",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantStarted = ChatMessage.AssistantMessage(
            id = 82L,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )
        val assistantFinished = assistantStarted.copy(content = "")
        // Second assistant iteration (after tool execution) that stops the loop.
        val assistantFinal = ChatMessage.AssistantMessage(
            id = 84L,
            sessionId = testSession.id,
            content = "Done",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = assistantStarted.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )
        val pendingToolCall = ToolCall(
            id = 83L,
            messageId = assistantStarted.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = "call_weather",
            // The authoritative arguments from output_item.done, not the malformed deltas.
            input = """{"location":"Paris"}""",
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )
        // Deltas that concatenate to malformed/incomplete JSON.
        val malformedDeltaJson = """{"loc"""
        val authoritativeArguments = """{"location":"Paris"}"""
        val toolCallDone = LLMStreamChunk.ToolCallDone(
            index = 0,
            id = "call_weather",
            name = "getWeather",
            arguments = authoritativeArguments
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Weather?", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id, "", userMessage.id, testModel, testSettings, agentRoleId = testRoleId, reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantStarted, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id, "", assistantStarted.id, testModel, testSettings, agentRoleId = testRoleId, reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantFinal, assistantStarted)
        // First iteration: a malformed delta is streamed for live UI, then the authoritative ToolCallDone
        // overrides it. Second iteration: plain content ends the loop.
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            flowOf(
                LLMStreamChunk.ToolCallChunk(
                    index = 0, id = "call_weather", name = "getWeather", argumentsDelta = malformedDeltaJson
                ).right(),
                toolCallDone.right(),
                LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
                LLMStreamChunk.Done.right()
            ),
            flowOf(
                LLMStreamChunk.ContentChunk("Done", finishReason = "stop").right(),
                LLMStreamChunk.Done.right()
            )
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(assistantStarted.id, "")
        } returns assistantFinished
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(assistantFinal.id, "Done")
        } returns assistantFinal

        val capturedRequests = mutableListOf<List<LLMCompletionResult.CompletionChoice.ToolCallRequest>>()
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(
                assistantStarted.id,
                capture(capturedRequests),
                listOf(toolDefinition)
            )
        } returns listOf(pendingToolCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(
                1L,
                testRoleId,
                listOf(pendingToolCall),
                listOf(toolDefinition),
                any(),
                any(),
                any()
            )
        } returns flowOf(
            ToolCallExecutionEvent.ToolCallCompleted(pendingToolCall)
        )

        orchestrator.processStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key", listOf(toolDefinition)),
                content = "Weather?",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        // The persisted request must carry the authoritative (corrected) arguments, not the malformed delta.
        assertEquals(1, capturedRequests.size)
        assertEquals(1, capturedRequests[0].size)
        assertEquals("getWeather", capturedRequests[0][0].name)
        assertEquals(authoritativeArguments, capturedRequests[0][0].arguments)
        assertEquals("call_weather", capturedRequests[0][0].toolCallId)
    }

    /**
     * Verifies that reasoning streamed during a tool-calling assistant step is replayed into the follow-up
     * streaming LLM request context alongside the tool result.
     */
    @Test
    fun `processStreamingTurn replays reasoning into follow-up tool-loop iteration`() = runTest {
        val reasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_loop_stream")
                put("status", "completed")
                put("format", "unknown")
                put("encrypted_content", "opaque-stream-loop")
            }
        )
        val sanitizedReasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_loop_stream")
                put("encrypted_content", "opaque-stream-loop")
            }
        )
        val toolDefinition = LocalMCPToolDefinition(
            id = 8L,
            name = "lookup",
            description = "Looks things up",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            outputSchema = null,
            isEnabled = true,
            createdAt = baseInstant,
            updatedAt = baseInstant,
            serverId = 1L,
            mcpToolName = "lookup"
        )
        val reasoningModel = testModel.copy()
        val reasoningSettings = ResponsesModelSettings(
            id = 5L,
            modelId = reasoningModel.id,
            name = "Default Responses",
            stream = true,
            replayReasoning = true
        )
        val userMessage = ChatMessage.UserMessage(
            id = 101L,
            sessionId = testSession.id,
            content = "Stream look up",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantToolStarted = ChatMessage.AssistantMessage(
            id = 102L,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = reasoningModel.id,
            settingsId = reasoningSettings.id
        )
        val assistantToolFinished = assistantToolStarted.copy(content = "")
        val assistantFinal = ChatMessage.AssistantMessage(
            id = 104L,
            sessionId = testSession.id,
            content = "Done.",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = assistantToolStarted.id,
            childrenMessageIds = emptyList(),
            modelId = reasoningModel.id,
            settingsId = reasoningSettings.id
        )
        val pendingToolCall = ToolCall(
            id = 103L,
            messageId = assistantToolStarted.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = "call_lookup_stream",
            input = "{\"key\":\"a\"}",
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Stream look up", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id, "", userMessage.id, reasoningModel, reasoningSettings, agentRoleId = testRoleId, reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantToolStarted, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id, "", assistantToolStarted.id, reasoningModel, reasoningSettings, agentRoleId = testRoleId, reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantFinal, assistantToolStarted)
        // First iteration streams reasoning + a tool call; second iteration ends the loop.
        val capturedContexts = mutableListOf<List<RawChatMessage>>()
        coEvery {
            llmApiClient.completeChatStreaming(capture(capturedContexts), any(), any(), any(), any(), any())
        } returnsMany listOf(
            flowOf(
                LLMStreamChunk.ReasoningDone(reasoningItem = reasoningItems[0]).right(),
                LLMStreamChunk.ToolCallChunk(
                    index = 0, id = "call_lookup_stream", name = "lookup", argumentsDelta = "{\"key\":\"a\"}"
                ).right(),
                LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
                LLMStreamChunk.Done.right()
            ),
            flowOf(
                LLMStreamChunk.ContentChunk("Done.", finishReason = "stop").right(),
                LLMStreamChunk.Done.right()
            )
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageReasoning(assistantToolStarted.id, sanitizedReasoningItems)
        } returns assistantToolFinished
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(assistantToolStarted.id, "")
        } returns assistantToolFinished
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(assistantFinal.id, "Done.")
        } returns assistantFinal
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(
                assistantToolStarted.id,
                any(),
                listOf(toolDefinition)
            )
        } returns listOf(pendingToolCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(
                1L, testRoleId, listOf(pendingToolCall), listOf(toolDefinition), any(), any(), any()
            )
        } returns flowOf(ToolCallExecutionEvent.ToolCallCompleted(pendingToolCall.copy(
            output = "{\"results\":[]}",
            status = ToolCallStatus.SUCCESS,
            durationMs = 5L
        )))

        orchestrator.processStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(
                    testProvider, reasoningModel, reasoningSettings, "api-key", listOf(toolDefinition)
                ),
                content = "Stream look up",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        // The follow-up request's context must carry the iteration-1 assistant tool-call message with the
        // reasoning items replayed so the next streaming LLM call sees the prior chain-of-thought.
        assertEquals(2, capturedContexts.size)
        val followUpAssistant = capturedContexts[1].filterIsInstance<RawChatMessage.Assistant>()
            .first { !it.toolCalls.isNullOrEmpty() }
        assertEquals(sanitizedReasoningItems, followUpAssistant.reasoningItems)
    }
}
