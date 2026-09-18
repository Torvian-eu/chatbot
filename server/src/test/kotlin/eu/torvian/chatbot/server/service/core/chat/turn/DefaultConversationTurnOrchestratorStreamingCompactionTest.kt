package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactionTurnState
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
import eu.torvian.chatbot.server.service.core.chat.compaction.PrimaryContextPreflight
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Covers the compaction preflight window and the compaction-completed event of a streaming turn. */
class DefaultConversationTurnOrchestratorStreamingCompactionTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

    /**
     * Verifies reviewer Finding 3 for the streaming path: a successful preflight feeds the verified
     * rolling window into the stream, and the placeholder row is created only after the preflight.
     */
    @Test
    fun `processStreamingTurn passes the preflight window to the stream and creates the placeholder after preflight`() =
        runTest {
            val streamingSettings = testSettings.copy(stream = true)
            val userMessage = ChatMessage.UserMessage(
                id = 141L,
                sessionId = testSession.id,
                content = "Summarize",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            )
            val assistantStarted = ChatMessage.AssistantMessage(
                id = 142L,
                sessionId = testSession.id,
                content = "",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = userMessage.id,
                childrenMessageIds = emptyList(),
                modelId = testModel.id,
                settingsId = streamingSettings.id
            )
            val assistantFinished = assistantStarted.copy(content = "Summarized answer")
            val windowMessages = listOf<RawChatMessage>(
                RawChatMessage.User(ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL + "prior"),
                RawChatMessage.User("recent")
            )

            coEvery {
                conversationTurnPersistence.saveUserMessage(testSession.id, "Summarize", null, any())
            } returns PersistedUserMessage(userMessage, null)
            coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
            coEvery { conversationCompactionService.beginTurn(1L, testSession.id, any()) } returns
                    CompactionTurnState.Disabled(testSession.id, mutableListOf()).right()
            coEvery { conversationCompactionService.preparePrimaryContext(any(), any(), any()) } returns
                    PrimaryContextPreflight(primaryMessages = windowMessages, persistedChunkIfAny = null).right()
            coEvery {
                conversationTurnPersistence.saveAssistantMessage(
                    testSession.id, "", userMessage.id, testModel, streamingSettings,
                    agentRoleId = testRoleId, reasoningItems = null,
                    completion = AssistantMessageCompletionState.InFlight
                )
            } returns PersistedAssistantMessage(assistantStarted, userMessage)
            val capturedStreamContexts = mutableListOf<List<RawChatMessage>>()
            coEvery {
                llmApiClient.completeChatStreaming(
                    capture(capturedStreamContexts), any(), any(), any(), any(), any()
                )
            } returns flowOf(
                LLMStreamChunk.ContentChunk("Summarized answer", finishReason = "stop").right(),
                LLMStreamChunk.Done.right()
            )
            coEvery {
                conversationTurnPersistence.updateAssistantMessageContent(assistantStarted.id, "Summarized answer")
            } returns assistantFinished

            val events = orchestrator.processStreamingTurn(
                ConversationTurnRequest(
                    userId = 1L,
                    session = testSession,
                    llmConfig = LLMConfig(testProvider, testModel, streamingSettings, "api-key"),
                    content = "Summarize",
                    parentMessageId = null,
                    fileReferences = emptyList(),
                    toolApprovalFlow = emptyFlow(),
                    operatorToolResultFlow = emptyFlow(),
                    turnControlSignal = TurnControlSignal()
                )
            ).toList()

            // The stream received the exact verified window from the preflight.
            assertEquals(windowMessages, capturedStreamContexts.single())
            // The placeholder is created only after the preflight succeeded.
            assertIs<ConversationTurnEvent.AssistantMessageStarted>(events[1])
            assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        }

    /**
     * Verifies reviewer Finding 3 for the streaming path: a failed preflight leaves no placeholder row
     * and never starts the stream.
     */
    @Test
    fun `processStreamingTurn preflight failure leaves no placeholder row and no stream`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 143L,
            sessionId = testSession.id,
            content = "Oversized stream",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Oversized stream", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { conversationCompactionService.beginTurn(1L, testSession.id, any()) } returns
                CompactionTurnState.Disabled(testSession.id, mutableListOf()).right()
        coEvery { conversationCompactionService.preparePrimaryContext(any(), any(), any()) } returns
                ConversationCompactionError.InvalidConfiguration("broken preference").left()

        val events = orchestrator.processStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                content = "Oversized stream",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        assertEquals(3, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertIs<ConversationTurnEvent.CompactionFailed>(events[1])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[2])
        coVerify(exactly = 0) {
            conversationTurnPersistence.saveAssistantMessage(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) }
    }

    /**
     * Verifies the emission rule of the compaction notification for the streaming path: when the preflight
     * persisted a chunk and the turn proceeds to the primary call, a `CompactionCompleted` event is emitted
     * immediately before the assistant step (placeholder creation + stream) that uses the chunk.
     */
    @Test
    fun `processStreamingTurn emits compaction completed before the primary call when a chunk was persisted`() =
        runTest {
            val streamingSettings = testSettings.copy(stream = true)
            val userMessage = ChatMessage.UserMessage(
                id = 181L,
                sessionId = testSession.id,
                content = "Summarize now",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            )
            val assistantStarted = ChatMessage.AssistantMessage(
                id = 182L,
                sessionId = testSession.id,
                content = "",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = userMessage.id,
                childrenMessageIds = emptyList(),
                modelId = testModel.id,
                settingsId = streamingSettings.id
            )
            val assistantFinished = assistantStarted.copy(content = "Streamed from summary")
            val persistedChunk = compactionChunk(id = 310L)
            val summaryMessages = listOf<RawChatMessage>(
                RawChatMessage.User(ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL + "prior")
            )

            coEvery {
                conversationTurnPersistence.saveUserMessage(testSession.id, "Summarize now", null, any())
            } returns PersistedUserMessage(userMessage, null)
            coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
            coEvery { conversationCompactionService.beginTurn(1L, testSession.id, any()) } returns
                CompactionTurnState.Disabled(testSession.id, mutableListOf()).right()
            coEvery { conversationCompactionService.preparePrimaryContext(any(), any(), any()) } returns
                PrimaryContextPreflight(
                    primaryMessages = summaryMessages,
                    persistedChunkIfAny = persistedChunk
                ).right()
            coEvery {
                conversationTurnPersistence.saveAssistantMessage(
                    testSession.id, "", userMessage.id, testModel, streamingSettings,
                    agentRoleId = testRoleId, reasoningItems = null,
                    completion = AssistantMessageCompletionState.InFlight
                )
            } returns PersistedAssistantMessage(assistantStarted, userMessage)
            coEvery {
                llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any())
            } returns flowOf(
                LLMStreamChunk.ContentChunk("Streamed from summary", finishReason = "stop").right(),
                LLMStreamChunk.Done.right()
            )
            coEvery {
                conversationTurnPersistence.updateAssistantMessageContent(assistantStarted.id, "Streamed from summary")
            } returns assistantFinished

            val events = orchestrator.processStreamingTurn(
                ConversationTurnRequest(
                    userId = 1L,
                    session = testSession,
                    llmConfig = LLMConfig(testProvider, testModel, streamingSettings, "api-key"),
                    content = "Summarize now",
                    parentMessageId = null,
                    fileReferences = emptyList(),
                    toolApprovalFlow = emptyFlow(),
                    operatorToolResultFlow = emptyFlow(),
                    turnControlSignal = TurnControlSignal()
                )
            ).toList()

            // UserMessageSaved, then the notification, then the placeholder/stream using the chunk.
            // (one ContentChunk produces exactly one AssistantMessageDelta before the finish event)
            assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
            val compactionEvent = assertIs<ConversationTurnEvent.CompactionCompleted>(events[1])
            assertEquals(persistedChunk, compactionEvent.chunk)
            assertIs<ConversationTurnEvent.AssistantMessageStarted>(events[2])
            assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[3])
            assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[4])
            assertEquals(ConversationTurnEvent.TurnCompleted, events[5])
        }

    /**
     * Verifies the emission rule of the compaction notification for the streaming path: no
     * `CompactionCompleted` is emitted when the preflight persisted nothing (fit/reuse/disabled paths).
     */
    @Test
    fun `processStreamingTurn emits no compaction completed when nothing was persisted`() = runTest {
        val streamingSettings = testSettings.copy(stream = true)
        val userMessage = ChatMessage.UserMessage(
            id = 191L,
            sessionId = testSession.id,
            content = "Streams without compaction",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantStarted = ChatMessage.AssistantMessage(
            id = 192L,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = streamingSettings.id
        )
        val assistantFinished = assistantStarted.copy(content = "Direct answer")

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Streams without compaction", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { conversationCompactionService.beginTurn(1L, testSession.id, any()) } returns
            CompactionTurnState.Disabled(testSession.id, mutableListOf()).right()
        coEvery { conversationCompactionService.preparePrimaryContext(any(), any(), any()) } returns
            PrimaryContextPreflight(primaryMessages = emptyList(), persistedChunkIfAny = null).right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id, "", userMessage.id, testModel, streamingSettings,
                agentRoleId = testRoleId, reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
            )
        } returns PersistedAssistantMessage(assistantStarted, userMessage)
        coEvery {
            llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } returns flowOf(
            LLMStreamChunk.ContentChunk("Direct answer", finishReason = "stop").right(),
            LLMStreamChunk.Done.right()
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(assistantStarted.id, "Direct answer")
        } returns assistantFinished

        val events = orchestrator.processStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, streamingSettings, "api-key"),
                content = "Streams without compaction",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        assertTrue(events.none { it is ConversationTurnEvent.CompactionCompleted })
        assertIs<ConversationTurnEvent.AssistantMessageStarted>(events[1])
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
    }
}
