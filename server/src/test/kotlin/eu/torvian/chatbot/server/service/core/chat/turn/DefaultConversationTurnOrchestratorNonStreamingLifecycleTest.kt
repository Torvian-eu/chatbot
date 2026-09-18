package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Covers the non-streaming turn lifecycle: persisted messages, completion and the paused-turn early stop. */
class DefaultConversationTurnOrchestratorNonStreamingLifecycleTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies the non-streaming path emits the same persisted message lifecycle for a simple turn.
     */
    @Test
    fun `processNonStreamingTurn emits user saved assistant saved and completed`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 11L,
            sessionId = testSession.id,
            content = "Hello",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantMessage = ChatMessage.AssistantMessage(
            id = 12L,
            sessionId = testSession.id,
            content = "Hi there",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )
        val completion = LLMCompletionResult(
            id = "completion-1",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = assistantMessage.content,
                    finishReason = "stop",
                    index = 0
                )
            ),
            usage = LLMCompletionResult.UsageStats(1, 1, 2),
            metadata = emptyMap()
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Hello", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns completion.right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                assistantMessage.content,
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantMessage, userMessage)

        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                content = "Hello",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        assertEquals(3, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[2])
    }

    /**
     * Verifies a pause observed at an iteration boundary completes the turn without issuing another LLM call.
     */
    @Test
    fun `processNonStreamingTurn stops before the next iteration when paused`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 51L,
            sessionId = testSession.id,
            content = "Pause here",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val turnControlSignal = TurnControlSignal().also { it.pause() }

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Pause here", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()

        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                content = "Pause here",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = turnControlSignal
            )
        ).toList()

        assertEquals(2, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[1])
        coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
    }
}
