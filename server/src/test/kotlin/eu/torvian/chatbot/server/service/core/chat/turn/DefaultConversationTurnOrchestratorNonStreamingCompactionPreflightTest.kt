package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactionTurnState
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
import eu.torvian.chatbot.server.service.core.chat.compaction.PrimaryContextPreflight
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
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
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Covers the compaction preflight of a non-streaming turn: its window, failure and follow-up unit. */
class DefaultConversationTurnOrchestratorNonStreamingCompactionPreflightTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies the compaction policy runs before the first primary call and the flattened source context
     * is passed to the LLM client.
     */
    @Test
    fun `processNonStreamingTurn runs compaction preflight before the first primary call`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 71L,
            sessionId = testSession.id,
            content = "Preflight",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantMessage = ChatMessage.AssistantMessage(
            id = 72L,
            sessionId = testSession.id,
            content = "Done",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Preflight", null, any())
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

        // The default base stub returns a disabled preflight; verify the policy is consulted exactly
        // once for a single-call turn, before the primary call. The Disabled state carries the units
        // the orchestrator handed to beginTurn, so the preflight sees the persisted user message.
        coEvery { conversationCompactionService.beginTurn(1L, testSession.id, any()) } coAnswers {
            CompactionTurnState.Disabled(
                testSession.id,
                thirdArg<List<ConversationContextUnit>>().toMutableList()
            ).right()
        }
        val capturedStates = mutableListOf<CompactionTurnState>()
        coEvery { conversationCompactionService.preparePrimaryContext(any(), any(), any()) } coAnswers {
            val state = firstArg<CompactionTurnState>()
            capturedStates.add(state)
            PrimaryContextPreflight(
                primaryMessages = state.units.flatMap { it.rawMessages },
                persistedChunkIfAny = null
            ).right()
        }

        orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                content = "Preflight",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        coVerify(exactly = 1) { conversationCompactionService.beginTurn(1L, testSession.id, any()) }
        coVerify(exactly = 1) { conversationCompactionService.preparePrimaryContext(any(), any(), any()) }
        // The preflight saw the state initialized from the persisted user message.
        assertEquals(listOf(userMessage.id), capturedStates.single().units.map { it.source.id })
    }

    /**
     * Verifies a compaction failure aborts the turn before any primary LLM call.
     */
    @Test
    fun `processNonStreamingTurn emits compaction failure and skips the primary call`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 81L,
            sessionId = testSession.id,
            content = "Oversized",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Oversized", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()

        coEvery { conversationCompactionService.beginTurn(1L, testSession.id, any()) } returns
            CompactionTurnState.Disabled(testSession.id, mutableListOf()).right()
        coEvery { conversationCompactionService.preparePrimaryContext(any(), any(), any()) } returns
            ConversationCompactionError.InvalidConfiguration("broken preference").left()

        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                content = "Oversized",
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
        // No primary call may be issued after a compaction failure.
        coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * Verifies reviewer Finding 3 for the non-streaming path: the follow-up preflight of a tool loop
     * sees the newly appended assistant/tool unit in the rolling window, so a compaction on the next
     * iteration covers the just-completed tool step.
     */
    @Test
    fun `processNonStreamingTurn follow-up preflight sees the appended assistant and tool unit`() = runTest {
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
            id = 121L,
            sessionId = testSession.id,
            content = "Find docs",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantToolMessage = ChatMessage.AssistantMessage(
            id = 122L,
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
            id = 123L,
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
            id = 131L,
            messageId = assistantToolMessage.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = "call-1",
            input = "{\"query\":\"docs\"}",
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

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Find docs", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            llmApiClient.completeChat(any(), any(), any(), any(), any(), any())
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
            ToolCallExecutionEvent.ToolCallCompleted(completedToolCall)
        )

        val preflightStates = mutableListOf<CompactionTurnState>()
        coEvery { conversationCompactionService.beginTurn(1L, testSession.id, any()) } coAnswers {
            CompactionTurnState.Disabled(
                testSession.id,
                thirdArg<List<ConversationContextUnit>>().toMutableList()
            ).right()
        }
        coEvery { conversationCompactionService.preparePrimaryContext(any(), any(), any()) } coAnswers {
            val state = firstArg<CompactionTurnState>()
            preflightStates.add(state)
            PrimaryContextPreflight(
                primaryMessages = state.units.flatMap { it.rawMessages },
                persistedChunkIfAny = null
            ).right()
        }

        orchestrator.processNonStreamingTurn(
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

        assertEquals(2, preflightStates.size)
        // The follow-up preflight saw the appended assistant/tool unit inside the rolling window.
        assertEquals(
            listOf(userMessage.id, assistantToolMessage.id),
            preflightStates[1].units.map { it.source.id }
        )
        val appendedUnit = preflightStates[1].units.last()
        assertEquals(SourceMessageSnapshot(assistantToolMessage.id, assistantToolMessage.updatedAt), appendedUnit.source)
        assertTrue(appendedUnit.rawMessages.any { it is RawChatMessage.Assistant })
        assertTrue(appendedUnit.rawMessages.any { it is RawChatMessage.Tool })
    }
}
