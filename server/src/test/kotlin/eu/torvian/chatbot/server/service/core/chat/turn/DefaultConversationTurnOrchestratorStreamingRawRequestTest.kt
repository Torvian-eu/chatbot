package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import io.mockk.coEvery
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers that the streaming path publishes the captured provider request before any content delta of
 * the step it belongs to.
 */
class DefaultConversationTurnOrchestratorStreamingRawRequestTest :
    DefaultConversationTurnOrchestratorStreamingTestBase() {

    @Test
    fun `a streaming step publishes its request before the assistant deltas`() = runTest {
        val streamingSettings = testSettings.copy(stream = true)
        val userMessage = ChatMessage.UserMessage(
            id = 601L,
            sessionId = testSession.id,
            content = "Hello",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val placeholder = ChatMessage.AssistantMessage(
            id = 602L,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = streamingSettings.id
        )
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
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight,
                usageStats = any(),
                responseDurationMs = any()
            )
        } returns PersistedAssistantMessage(placeholder, userMessage)
        coEvery {
            llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            arg<suspend (RawLlmRequestPayload) -> Unit>(7).invoke(payload("streamed-request"))
            flow {
                emit(LLMStreamChunk.ContentChunk("Hello there", finishReason = "stop").right())
                emit(LLMStreamChunk.Done.right())
            }
        }
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Hello there",
                usageStats = any(),
                responseDurationMs = any()
            )
        } returns placeholder.copy(content = "Hello there")

        val events = orchestrator.processStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, streamingSettings, "api-key"),
                resolvedCompaction = defaultResolvedCompaction,
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
        val capture = assertIs<ConversationTurnEvent.RawLlmRequest>(events[2])
        assertEquals("streamed-request", capture.payload.body)
        assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[3])
        assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[4])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[5])
    }

    /** Builds the payload the stub publishing client hands to the step observer. */
    private fun payload(marker: String) = RawLlmRequestPayload(
        method = "POST",
        url = "https://api.openai.com/v1/chat/completions",
        headers = mapOf("Authorization" to "Bearer test-key"),
        body = marker
    )
}
