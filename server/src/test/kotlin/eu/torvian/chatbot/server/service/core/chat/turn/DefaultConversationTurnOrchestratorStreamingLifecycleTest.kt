package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Covers the streaming turn lifecycle: live delta emission, message finalization and reasoning persistence. */
class DefaultConversationTurnOrchestratorStreamingLifecycleTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

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
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
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
                testSession.id,
                "",
                userMessage.id,
                reasoningModel,
                reasoningSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
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
}
