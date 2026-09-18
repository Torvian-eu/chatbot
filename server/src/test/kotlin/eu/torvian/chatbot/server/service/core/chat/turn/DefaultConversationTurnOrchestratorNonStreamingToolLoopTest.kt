package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Covers the non-streaming tool loop: persisted tool calls, tool lifecycle events and the follow-up unit. */
class DefaultConversationTurnOrchestratorNonStreamingToolLoopTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies the shared loop persists tool calls, emits tool lifecycle events, and appends results back into context.
     */
    @Test
    fun `processNonStreamingTurn appends tool calls and results for follow-up iteration`() = runTest {
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
        val userMessage = ChatMessage.UserMessage(
            id = 21L,
            sessionId = testSession.id,
            content = "Find docs",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantToolMessage = ChatMessage.AssistantMessage(
            id = 22L,
            sessionId = testSession.id,
            content = "I'll search the docs.",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )
        val assistantFinalMessage = ChatMessage.AssistantMessage(
            id = 23L,
            sessionId = testSession.id,
            content = "Here are the docs.",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = assistantToolMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )
        val pendingToolCall = ToolCall(
            id = 31L,
            messageId = assistantToolMessage.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = "call-1",
            input = "{\"query\":\"docs\"}",
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )
        val executingToolCall = pendingToolCall.copy(status = ToolCallStatus.EXECUTING)
        val completedToolCall = pendingToolCall.copy(
            output = "{\"results\":[]}",
            status = ToolCallStatus.SUCCESS,
            durationMs = 5L
        )
        val firstCompletion = LLMCompletionResult(
            id = "completion-1",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = assistantToolMessage.content,
                    finishReason = "tool_calls",
                    index = 0,
                    toolCalls = listOf(
                        LLMCompletionResult.CompletionChoice.ToolCallRequest(
                            name = toolDefinition.name,
                            arguments = pendingToolCall.input,
                            toolCallId = pendingToolCall.toolCallId
                        )
                    )
                )
            ),
            usage = LLMCompletionResult.UsageStats(1, 1, 2),
            metadata = emptyMap()
        )
        val secondCompletion = LLMCompletionResult(
            id = "completion-2",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = assistantFinalMessage.content,
                    finishReason = "stop",
                    index = 0
                )
            ),
            usage = LLMCompletionResult.UsageStats(1, 1, 2),
            metadata = emptyMap()
        )
        val capturedContexts = mutableListOf<List<RawChatMessage>>()

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Find docs", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            llmApiClient.completeChat(capture(capturedContexts), any(), any(), any(), any(), any())
        } returnsMany listOf(firstCompletion.right(), secondCompletion.right())
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                assistantToolMessage.content,
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantToolMessage, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                assistantFinalMessage.content,
                assistantToolMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantFinalMessage, assistantToolMessage)
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(
                assistantToolMessage.id,
                any(),
                listOf(toolDefinition)
            )
        } returns listOf(pendingToolCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(
                ToolCallExecutionContext(
                    userId = 1L,
                    sessionId = 1L,
                    sessionName = "Session",
                    agentRoleId = testRoleId
                ),
                listOf(pendingToolCall),
                listOf(toolDefinition),
                any(),
                any(),
                any()
            )
        } returns flowOf(
            ToolCallExecutionEvent.ToolCallApprovalRequested(pendingToolCall),
            ToolCallExecutionEvent.ToolCallExecuting(executingToolCall),
            ToolCallExecutionEvent.ToolCallCompleted(completedToolCall)
        )

        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key", listOf(toolDefinition)),
                content = "Find docs",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        assertEquals(8, events.size)
        assertIs<ConversationTurnEvent.ToolCallsReceived>(events[2])
        assertIs<ConversationTurnEvent.ToolCallApprovalRequested>(events[3])
        assertIs<ConversationTurnEvent.ToolCallExecuting>(events[4])
        assertIs<ConversationTurnEvent.ToolExecutionCompleted>(events[5])
        assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[6])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[7])
        assertEquals(2, capturedContexts.size)
        assertEquals(listOf("user", "assistant", "tool"), capturedContexts[1].map { it.role })
        assertEquals("{\"results\":[]}", capturedContexts[1].last().content)
    }
}
