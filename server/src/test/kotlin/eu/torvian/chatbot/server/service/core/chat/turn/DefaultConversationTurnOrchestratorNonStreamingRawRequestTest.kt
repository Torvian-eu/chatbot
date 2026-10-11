package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import io.mockk.coEvery
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers which turns of the non-streaming path publish a captured provider request, and where the
 * capture sits relative to the transcript events of the step that produced it.
 */
class DefaultConversationTurnOrchestratorNonStreamingRawRequestTest :
    DefaultConversationTurnOrchestratorTestBase() {

    /** Publishes one payload through the observer the step runner supplied. */
    private suspend fun publish(observer: suspend (RawLlmRequestPayload) -> Unit, marker: String) {
        observer(
            RawLlmRequestPayload(
                method = "POST",
                url = "https://api.openai.com/v1/chat/completions",
                headers = mapOf("Authorization" to "Bearer test-key"),
                body = marker
            )
        )
    }

    @Test
    fun `a completed step publishes its request after the user message and before the assistant message`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 501L,
            sessionId = testSession.id,
            content = "Hello",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantMessage = ChatMessage.AssistantMessage(
            id = 502L,
            sessionId = testSession.id,
            content = "Answer",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Hello", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            publish(arg(7), "first-request")
            completionResult("Answer").right()
        }
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Answer",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                usageStats = any(),
                completion = any(),
                responseDurationMs = any()
            )
        } returns PersistedAssistantMessage(assistantMessage, userMessage)

        val events = orchestrator.processNonStreamingTurn(request("Hello")).toList()

        assertEquals(4, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        val capture = assertIs<ConversationTurnEvent.RawLlmRequest>(events[1])
        assertEquals("first-request", capture.payload.body)
        assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[2])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[3])
    }

    @Test
    fun `a failed provider call still publishes its request`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 511L,
            sessionId = testSession.id,
            content = "Fail",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Fail", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            publish(arg(7), "failed-request")
            LLMCompletionError.ApiError(500, "boom", null).left()
        }
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                usageStats = null,
                completion = any(),
                responseDurationMs = any()
            )
        } returns PersistedAssistantMessage(
            ChatMessage.AssistantMessage(
                id = 512L,
                sessionId = testSession.id,
                content = "",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = userMessage.id,
                childrenMessageIds = emptyList(),
                modelId = testModel.id,
                settingsId = testSettings.id
            ),
            userMessage
        )

        val events = orchestrator.processNonStreamingTurn(request("Fail")).toList()

        val capture = events.filterIsInstance<ConversationTurnEvent.RawLlmRequest>().single()
        assertEquals("failed-request", capture.payload.body)
    }

    /** Builds a one-choice completion. */
    private fun completionResult(content: String) = LLMCompletionResult(
        id = "completion-raw-request",
        choices = listOf(
            LLMCompletionResult.CompletionChoice(
                role = "assistant",
                content = content,
                finishReason = "stop",
                index = 0
            )
        ),
        usage = UsageStats(1, 1, 2),
        metadata = emptyMap()
    )

    /** Builds a non-streaming turn request on the shared fixtures. */
    private fun request(content: String) = ConversationTurnRequest(
        userId = 1L,
        session = testSession,
        llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
        resolvedCompaction = defaultResolvedCompaction,
        content = content,
        parentMessageId = null,
        fileReferences = emptyList(),
        toolApprovalFlow = emptyFlow(),
        operatorToolResultFlow = emptyFlow(),
        turnControlSignal = TurnControlSignal()
    )
}
