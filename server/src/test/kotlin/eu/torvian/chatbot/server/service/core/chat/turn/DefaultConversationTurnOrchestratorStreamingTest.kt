package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.server.service.core.chat.compaction.CompactionTurnState
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
import eu.torvian.chatbot.server.service.core.chat.compaction.PrimaryContextPreflight
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
 * Verifies the streaming path of [DefaultConversationTurnOrchestrator]'s shared assistant/tool loop,
 * including live deltas, reasoning persistence, and authoritative tool-call overrides.
 */
class DefaultConversationTurnOrchestratorStreamingTest : DefaultConversationTurnOrchestratorTestBase() {

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
                reasoningItems = null
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
                reasoningItems = null
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
                reasoningItems = null
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
                reasoningItems = null
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
                1L,
                testRoleId,
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

    /**
     * Verifies that reasoning streamed during a tool-calling assistant step is replayed into the follow-up
     * streaming LLM request context alongside the tool result.
     */
    @Test
    fun `processStreamingTurn replays reasoning into follow-up tool-loop iteration`() = runTest {
        val reasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_loop_stream")
                put("status", "completed")
                put("format", "unknown")
                put("encrypted_content", "opaque-stream-loop")
            }
        )
        val sanitizedReasoningItems = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_loop_stream")
                put("encrypted_content", "opaque-stream-loop")
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
            id = 5L,
            modelId = reasoningModel.id,
            name = "Default Responses",
            stream = true,
            replayReasoning = true
        )
        val userMessage = ChatMessage.UserMessage(
            id = 101L,
            sessionId = testSession.id,
            content = "Stream look up",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val assistantToolStarted = ChatMessage.AssistantMessage(
            id = 102L,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = reasoningModel.id,
            settingsId = reasoningSettings.id
        )
        val assistantToolFinished = assistantToolStarted.copy(content = "")
        val assistantFinal = ChatMessage.AssistantMessage(
            id = 104L,
            sessionId = testSession.id,
            content = "Done.",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = assistantToolStarted.id,
            childrenMessageIds = emptyList(),
            modelId = reasoningModel.id,
            settingsId = reasoningSettings.id
        )
        val pendingToolCall = ToolCall(
            id = 103L,
            messageId = assistantToolStarted.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = "call_lookup_stream",
            input = "{\"key\":\"a\"}",
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Stream look up", null, any())
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
                reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantToolStarted, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                assistantToolStarted.id,
                reasoningModel,
                reasoningSettings,
                agentRoleId = testRoleId,
                reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantFinal, assistantToolStarted)
        // First iteration streams reasoning + a tool call; second iteration ends the loop.
        val capturedContexts = mutableListOf<List<RawChatMessage>>()
        coEvery {
            llmApiClient.completeChatStreaming(capture(capturedContexts), any(), any(), any(), any(), any())
        } returnsMany listOf(
            flowOf(
                LLMStreamChunk.ReasoningDone(reasoningItem = reasoningItems[0]).right(),
                LLMStreamChunk.ToolCallChunk(
                    index = 0, id = "call_lookup_stream", name = "lookup", argumentsDelta = "{\"key\":\"a\"}"
                ).right(),
                LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
                LLMStreamChunk.Done.right()
            ),
            flowOf(
                LLMStreamChunk.ContentChunk("Done.", finishReason = "stop").right(),
                LLMStreamChunk.Done.right()
            )
        )
        coEvery {
            conversationTurnPersistence.updateAssistantMessageReasoning(
                assistantToolStarted.id,
                sanitizedReasoningItems
            )
        } returns assistantToolFinished
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(assistantToolStarted.id, "")
        } returns assistantToolFinished
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(assistantFinal.id, "Done.")
        } returns assistantFinal
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(
                assistantToolStarted.id,
                any(),
                listOf(toolDefinition)
            )
        } returns listOf(pendingToolCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(
                1L, testRoleId, listOf(pendingToolCall), listOf(toolDefinition), any(), any(), any()
            )
        } returns flowOf(
            ToolCallExecutionEvent.ToolCallCompleted(
                pendingToolCall.copy(
                    output = "{\"results\":[]}",
                    status = ToolCallStatus.SUCCESS,
                    durationMs = 5L
                )
            )
        )

        orchestrator.processStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(
                    testProvider, reasoningModel, reasoningSettings, "api-key", listOf(toolDefinition)
                ),
                content = "Stream look up",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        // The follow-up request's context must carry the iteration-1 assistant tool-call message with the
        // reasoning items replayed so the next streaming LLM call sees the prior chain-of-thought.
        assertEquals(2, capturedContexts.size)
        val followUpAssistant = capturedContexts[1].filterIsInstance<RawChatMessage.Assistant>()
            .first { !it.toolCalls.isNullOrEmpty() }
        assertEquals(sanitizedReasoningItems, followUpAssistant.reasoningItems)
    }

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
                    agentRoleId = testRoleId, reasoningItems = null
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
     * Verifies the FR-12 emission rule for the streaming path: when the preflight persisted a chunk and
     * the turn proceeds to the primary call, a `CompactionCompleted` event is emitted immediately
     * before the assistant step (placeholder creation + stream) that uses the chunk.
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
                    agentRoleId = testRoleId, reasoningItems = null
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
     * Verifies the FR-12 emission rule for the streaming path: no `CompactionCompleted` is emitted when
     * the preflight persisted nothing (fit/reuse/disabled paths).
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
                agentRoleId = testRoleId, reasoningItems = null
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
