package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.runtime.TurnControlSignal
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
import io.mockk.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies the non-streaming path of [DefaultConversationTurnOrchestrator]'s shared assistant/tool loop.
 */
class DefaultConversationTurnOrchestratorNonStreamingTest : DefaultConversationTurnOrchestratorTestBase() {

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
     * Verifies the shared loop persists tool calls, emits tool lifecycle events, and appends results back into context.
     */
    @Test
    fun `processNonStreamingTurn appends tool calls and results for follow-up iteration`() = runTest {
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
            id = 21L,
            sessionId = testSession.id,
            content = "Find docs",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantToolMessage = ChatMessage.AssistantMessage(
            id = 22L,
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
            id = 23L,
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
            id = 31L,
            messageId = assistantToolMessage.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = "call-1",
            input = "{\"query\":\"docs\"}",
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )
        val executingToolCall = pendingToolCall.copy(status = ToolCallStatus.EXECUTING)
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
        val capturedContexts = mutableListOf<List<RawChatMessage>>()

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Find docs", null, any())
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
                1L,
                testRoleId,
                listOf(pendingToolCall),
                listOf(toolDefinition),
                any(),
                any(),
                any()
            )
        } returns flowOf(
            ToolCallExecutionEvent.ToolCallApprovalRequested(pendingToolCall),
            ToolCallExecutionEvent.ToolCallExecuting(executingToolCall),
            ToolCallExecutionEvent.ToolCallCompleted(completedToolCall)
        )

        val events = orchestrator.processNonStreamingTurn(
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

        assertEquals(8, events.size)
        assertIs<ConversationTurnEvent.ToolCallsReceived>(events[2])
        assertIs<ConversationTurnEvent.ToolCallApprovalRequested>(events[3])
        assertIs<ConversationTurnEvent.ToolCallExecuting>(events[4])
        assertIs<ConversationTurnEvent.ToolExecutionCompleted>(events[5])
        assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[6])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[7])
        assertEquals(2, capturedContexts.size)
        assertEquals(listOf("user", "assistant", "tool"), capturedContexts[1].map { it.role })
        assertEquals("{\"results\":[]}", capturedContexts[1].last().content)
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
                1L,
                testRoleId,
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
                1L,
                testRoleId,
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

    /**
     * Verifies the FR-12 emission rule for the non-streaming path: when the preflight persisted a
     * chunk and the turn proceeds to the primary call, a `CompactionCompleted` event is emitted
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
     * Verifies the FR-12 emission rule for the non-streaming path: no `CompactionCompleted` is emitted
     * when the preflight persisted nothing (fit/reuse/disabled paths).
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
