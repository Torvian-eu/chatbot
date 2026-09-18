package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
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
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Covers how streamed tool-call deltas are assembled into the persisted, executed tool calls. */
class DefaultConversationTurnOrchestratorStreamingToolCallAssemblyTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

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
                testSession.id,
                "",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
            )
        } returns PersistedAssistantMessage(assistantStarted, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                assistantStarted.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
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
}
