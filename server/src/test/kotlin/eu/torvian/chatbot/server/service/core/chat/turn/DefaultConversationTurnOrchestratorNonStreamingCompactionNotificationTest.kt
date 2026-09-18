package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactionTurnState
import eu.torvian.chatbot.server.service.core.chat.compaction.PrimaryContextPreflight
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Covers the compaction-completed event emitted before the primary call of a non-streaming turn. */
class DefaultConversationTurnOrchestratorNonStreamingCompactionNotificationTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies the emission rule of the compaction notification for the non-streaming path: when the preflight
     * persisted a chunk and the turn proceeds to the primary call, a `CompactionCompleted` event is emitted
     * immediately before the assistant step that uses the chunk.
     */
    @Test
    fun `processNonStreamingTurn emits compaction completed before the primary call when a chunk was persisted`() =
        runTest {
            val userMessage = ChatMessage.UserMessage(
                id = 151L,
                sessionId = testSession.id,
                content = "Compacted input",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            )
            val assistantMessage = ChatMessage.AssistantMessage(
                id = 152L,
                sessionId = testSession.id,
                content = "Answered from summary",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = userMessage.id,
                childrenMessageIds = emptyList(),
                modelId = testModel.id,
                settingsId = testSettings.id
            )
            val persistedChunk = compactionChunk(id = 300L)
            val summaryMessages = listOf<RawChatMessage>(
                RawChatMessage.User(ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL + "prior")
            )

            coEvery {
                conversationTurnPersistence.saveUserMessage(testSession.id, "Compacted input", null, any())
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
                llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any())
            } returns LLMCompletionResult(
                id = "c",
                choices = listOf(
                    LLMCompletionResult.CompletionChoice(
                        role = "assistant", content = assistantMessage.content, finishReason = "stop", index = 0
                    )
                ),
                usage = LLMCompletionResult.UsageStats(1, 1, 2)
            ).right()
            coEvery {
                conversationTurnPersistence.saveAssistantMessage(
                    testSession.id, assistantMessage.content, userMessage.id, testModel, testSettings,
                    agentRoleId = testRoleId, reasoningItems = null
                )
            } returns PersistedAssistantMessage(assistantMessage, userMessage)

            val events = orchestrator.processNonStreamingTurn(
                ConversationTurnRequest(
                    userId = 1L,
                    session = testSession,
                    llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                    content = "Compacted input",
                    parentMessageId = null,
                    fileReferences = emptyList(),
                    toolApprovalFlow = emptyFlow(),
                    operatorToolResultFlow = emptyFlow(),
                    turnControlSignal = TurnControlSignal()
                )
            ).toList()

            // UserMessageSaved, then the notification, then the assistant response using the chunk.
            assertEquals(4, events.size)
            assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
            val compactionEvent = assertIs<ConversationTurnEvent.CompactionCompleted>(events[1])
            assertEquals(persistedChunk, compactionEvent.chunk)
            assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[2])
            assertEquals(ConversationTurnEvent.TurnCompleted, events[3])
            // The primary call received the summary window the chunk backs.
            coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
        }

    /**
     * Verifies the emission rule of the compaction notification for the non-streaming path: no
     * `CompactionCompleted` is emitted when the preflight persisted nothing (fit/reuse/disabled paths).
     */
    @Test
    fun `processNonStreamingTurn emits no compaction completed when nothing was persisted`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 161L,
            sessionId = testSession.id,
            content = "Fits threshold",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantMessage = ChatMessage.AssistantMessage(
            id = 162L,
            sessionId = testSession.id,
            content = "Direct answer",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Fits threshold", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any())
        } returns LLMCompletionResult(
            id = "c",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant", content = assistantMessage.content, finishReason = "stop", index = 0
                )
            ),
            usage = LLMCompletionResult.UsageStats(1, 1, 2)
        ).right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id, assistantMessage.content, userMessage.id, testModel, testSettings,
                agentRoleId = testRoleId, reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantMessage, userMessage)

        // The default base stub returns persistedChunkIfAny = null.
        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                content = "Fits threshold",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        assertTrue(events.none { it is ConversationTurnEvent.CompactionCompleted })
        assertEquals(3, events.size)
        assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
    }
}
