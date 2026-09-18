package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.outputLimitExceededCompletionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Covers a truncated non-streaming response and the tool calls it must never persist or execute. */
class DefaultConversationTurnOrchestratorNonStreamingTruncationTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies that the persisted content has no in-content notice and is flagged as a length-limit failure, that
     * the turn ends, and that the tool calls parsed from the truncated response are neither persisted nor executed.
     */
    @Test
    fun `processNonStreamingTurn flags a truncated response and never persists or executes its tool calls`() = runTest {
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
            id = 611L,
            sessionId = testSession.id,
            content = "Too long",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val oversizedContent = "c".repeat(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS + 3)
        val truncatedContent = oversizedContent.take(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
        val expectedFailure =
            outputLimitExceededCompletionState(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
        val truncatedAssistantMessage = ChatMessage.AssistantMessage(
            id = 612L,
            sessionId = testSession.id,
            content = truncatedContent,
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id,
            isComplete = expectedFailure.isComplete,
            incompleteCause = expectedFailure.incompleteCause,
            errorCode = expectedFailure.errorCode,
            errorMessage = expectedFailure.errorMessage
        )
        val truncatedCompletion = LLMCompletionResult(
            id = "completion-truncated",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = oversizedContent,
                    finishReason = "tool_calls",
                    index = 0,
                    toolCalls = listOf(
                        LLMCompletionResult.CompletionChoice.ToolCallRequest(
                            name = toolDefinition.name,
                            arguments = "{\"query\":\"docs\"}",
                            toolCallId = "call-truncated"
                        )
                    )
                )
            ),
            usage = LLMCompletionResult.UsageStats(1, 1, 2),
            metadata = emptyMap()
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Too long", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns truncatedCompletion.right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                truncatedContent,
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        } returns PersistedAssistantMessage(truncatedAssistantMessage, userMessage)

        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key", listOf(toolDefinition)),
                content = "Too long",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        assertEquals(3, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        val savedTruncated = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
        assertEquals(truncatedContent, savedTruncated.assistantMessage.content)
        assertEquals(AssistantMessageErrorCode.OUTPUT_LIMIT_EXCEEDED, savedTruncated.assistantMessage.errorCode)
        assertFalse(truncatedContent.contains("[Output truncated"))
        assertEquals(ConversationTurnEvent.TurnCompleted, events[2])
        // The truncated response terminates the tool loop, so nothing is persisted and nothing executes.
        assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
        coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
        verify(exactly = 0) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
    }
}
