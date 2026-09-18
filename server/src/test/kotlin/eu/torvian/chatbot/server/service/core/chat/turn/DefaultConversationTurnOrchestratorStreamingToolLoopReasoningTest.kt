package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Covers reasoning replay into the follow-up iteration of a streaming tool loop. */
class DefaultConversationTurnOrchestratorStreamingToolLoopReasoningTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

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
                testSession.id,
                "",
                userMessage.id,
                reasoningModel,
                reasoningSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
            )
        } returns PersistedAssistantMessage(assistantToolStarted, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                assistantToolStarted.id,
                reasoningModel,
                reasoningSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
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
            conversationTurnPersistence.updateAssistantMessageReasoning(
                assistantToolStarted.id,
                sanitizedReasoningItems
            )
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
                ToolCallExecutionContext(userId = 1L, sessionId = 1L, sessionName = "Session", agentRoleId = testRoleId),
                listOf(pendingToolCall),
                listOf(toolDefinition),
                any(),
                any(),
                any()
            )
        } returns flowOf(
            ToolCallExecutionEvent.ToolCallCompleted(
                pendingToolCall.copy(
                    output = "{\"results\":[]}",
                    status = ToolCallStatus.SUCCESS,
                    durationMs = 5L
                )
            )
        )

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
