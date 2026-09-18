package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
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
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Covers reasoning persistence and the replay of reasoning items into a non-streaming tool-loop iteration. */
class DefaultConversationTurnOrchestratorNonStreamingReasoningTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies that reasoning items in a non-streaming result are persisted with the assistant message.
     */
    @Test
    fun `processNonStreamingTurn persists reasoning items from the result`() = runTest {
        val reasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_1")
                put("encrypted_content", "opaque")
            }
        )
        val userMessage = ChatMessage.UserMessage(
            id = 61L,
            sessionId = testSession.id,
            content = "Reason",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val reasoningModel = testModel.copy()
        val reasoningSettings = ResponsesModelSettings(
            id = 2L,
            modelId = reasoningModel.id,
            name = "Default Responses",
            stream = false,
            replayReasoning = true
        )
        val assistantMessage = ChatMessage.AssistantMessage(
            id = 62L,
            sessionId = testSession.id,
            content = "Here is the answer",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = reasoningModel.id,
            settingsId = reasoningSettings.id,
            reasoningItems = reasoningItems
        )
        val completion = LLMCompletionResult(
            id = "resp-1",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = assistantMessage.content,
                    finishReason = "stop",
                    index = 0
                )
            ),
            usage = LLMCompletionResult.UsageStats(1, 1, 2),
            reasoningItems = reasoningItems
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(
                testSession.id,
                "Reason",
                null,
                any()
            )
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns completion.right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                assistantMessage.content,
                userMessage.id,
                reasoningModel,
                reasoningSettings,
                agentRoleId = testRoleId,
                reasoningItems = reasoningItems
            )
        } returns PersistedAssistantMessage(assistantMessage, userMessage)

        orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, reasoningModel, reasoningSettings, "api-key"),
                content = "Reason",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                assistantMessage.content,
                userMessage.id,
                reasoningModel,
                reasoningSettings,
                agentRoleId = testRoleId,
                reasoningItems = reasoningItems
            )
        }
        // The capability recorder must observe the model and its reasoning items so later replays can
        // adapt what is sent to this model.
        coVerify(exactly = 1) {
            reasoningCapabilityRecorder.record(reasoningModel, reasoningItems)
        }
    }

    /**
     * Verifies that reasoning emitted during a non-streaming tool-calling step is replayed into the follow-up
     * LLM request context alongside the tool result.
     */
    @Test
    fun `processNonStreamingTurn replays reasoning into follow-up tool-loop iteration`() = runTest {
        val reasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_loop")
                put("encrypted_content", "opaque-nonstream")
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
            id = 4L,
            modelId = reasoningModel.id,
            name = "Default Responses",
            stream = false,
            replayReasoning = true
        )
        val userMessage = ChatMessage.UserMessage(
            id = 91L,
            sessionId = testSession.id,
            content = "Look up data",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantToolMessage = ChatMessage.AssistantMessage(
            id = 92L,
            sessionId = testSession.id,
            content = "I will look it up.",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = reasoningModel.id,
            settingsId = reasoningSettings.id,
            reasoningItems = reasoningItems
        )
        val assistantFinalMessage = ChatMessage.AssistantMessage(
            id = 93L,
            sessionId = testSession.id,
            content = "Done.",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = assistantToolMessage.id,
            childrenMessageIds = emptyList(),
            modelId = reasoningModel.id,
            settingsId = reasoningSettings.id
        )
        val pendingToolCall = ToolCall(
            id = 94L,
            messageId = assistantToolMessage.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = "call_lookup",
            input = "{\"key\":\"a\"}",
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )
        val completedToolCall = pendingToolCall.copy(
            output = "{\"results\":[]}",
            status = ToolCallStatus.SUCCESS,
            durationMs = 5L
        )
        val firstCompletion = LLMCompletionResult(
            id = "resp-1",
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
            reasoningItems = reasoningItems
        )
        val secondCompletion = LLMCompletionResult(
            id = "resp-2",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = assistantFinalMessage.content,
                    finishReason = "stop",
                    index = 0
                )
            ),
            usage = LLMCompletionResult.UsageStats(1, 1, 2)
        )
        val capturedContexts = mutableListOf<List<RawChatMessage>>()

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Look up data", null, any())
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
                reasoningModel,
                reasoningSettings,
                agentRoleId = testRoleId,
                reasoningItems = reasoningItems
            )
        } returns PersistedAssistantMessage(assistantToolMessage, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                assistantFinalMessage.content,
                assistantToolMessage.id,
                reasoningModel,
                reasoningSettings,
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
        } returns flowOf(ToolCallExecutionEvent.ToolCallCompleted(completedToolCall))

        orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(
                    testProvider, reasoningModel, reasoningSettings, "api-key", listOf(toolDefinition)
                ),
                content = "Look up data",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        // The follow-up request's context must carry the iteration-1 assistant message with the reasoning
        // items replayed so the next LLM call sees the prior chain-of-thought.
        assertEquals(2, capturedContexts.size)
        val followUpAssistant = capturedContexts[1].filterIsInstance<RawChatMessage.Assistant>()
            .first { !it.toolCalls.isNullOrEmpty() }
        assertEquals(reasoningItems, followUpAssistant.reasoningItems)
    }
}
