package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.DefaultChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallOrchestrator
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Verifies the extracted turn orchestrator keeps the shared assistant/tool loop behavior intact.
 */
class DefaultConversationTurnOrchestratorTest {
    private lateinit var llmApiClient: LLMApiClient
    private lateinit var toolCallOrchestrator: ToolCallOrchestrator
    private lateinit var conversationTurnPersistence: ConversationTurnPersistence
    private lateinit var orchestrator: DefaultConversationTurnOrchestrator

    private val baseInstant = Instant.fromEpochMilliseconds(1234567890000L)

    private val testModel = LLMModel(
        id = 1L,
        name = "gpt-4o-mini",
        providerId = 1L,
        active = true,
        displayName = "GPT-4o mini"
    )

    private val testProvider = LLMProvider(
        id = 1L,
        apiKeyId = "test-key",
        name = "OpenAI",
        description = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    private val testSettings = ChatModelSettings(
        id = 1L,
        name = "Default",
        modelId = 1L,
        systemMessage = "You are a helpful assistant.",
        temperature = 0.2f,
        maxTokens = 1000,
        customParams = null,
        stream = false
    )

    private val testSession = ChatSession(
        id = 1L,
        name = "Session",
        createdAt = baseInstant,
        updatedAt = baseInstant,
        groupId = null,
        currentModelId = 1L,
        currentSettingsId = 1L,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    /**
     * Recreates the orchestrator with fresh mocks for each test.
     */
    @BeforeEach
    fun setUp() {
        llmApiClient = mockk()
        toolCallOrchestrator = mockk()
        conversationTurnPersistence = mockk()

        orchestrator = DefaultConversationTurnOrchestrator(
            llmApiClient = llmApiClient,
            toolCallOrchestrator = toolCallOrchestrator,
            toolResultContentBuilder = DefaultToolResultContentBuilder(),
            chatContextBuilder = DefaultChatContextBuilder(
                fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
                toolResultContentBuilder = DefaultToolResultContentBuilder()
            ),
            conversationTurnPersistence = conversationTurnPersistence
        )
    }

    /**
     * Clears mocks after each test run.
     */
    @AfterEach
    fun tearDown() {
        clearMocks(llmApiClient, toolCallOrchestrator, conversationTurnPersistence)
    }

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
                listOf(pendingToolCall),
                listOf(toolDefinition),
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
                turnControlSignal = turnControlSignal
            )
        ).toList()

        assertEquals(2, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[1])
        coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
    }

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
                reasoningItems
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
                reasoningItems
            )
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
                testSession.id, "", userMessage.id, reasoningModel, reasoningSettings, reasoningItems = null
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
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageReasoning(assistantStarted.id, reasoningItems)
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
                testSession.id, "", userMessage.id, testModel, testSettings, reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantStarted, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id, "", assistantStarted.id, testModel, testSettings, reasoningItems = null
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
                listOf(pendingToolCall),
                listOf(toolDefinition),
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
                reasoningItems
            )
        } returns PersistedAssistantMessage(assistantToolMessage, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                assistantFinalMessage.content,
                assistantToolMessage.id,
                reasoningModel,
                reasoningSettings,
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
                listOf(pendingToolCall),
                listOf(toolDefinition),
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
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        // The follow-up request's context must carry the iteration-1 assistant message with the reasoning
        // items replayed so the next LLM call sees the prior chain-of-thought.
        assertEquals(2, capturedContexts.size)
        val followUpAssistant = capturedContexts[1].filterIsInstance<RawChatMessage.Assistant>()
            .first { it.toolCalls != null && it.toolCalls.isNotEmpty() }
        assertEquals(reasoningItems, followUpAssistant.reasoningItems)
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
                testSession.id, "", userMessage.id, reasoningModel, reasoningSettings, reasoningItems = null
            )
        } returns PersistedAssistantMessage(assistantToolStarted, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id, "", assistantToolStarted.id, reasoningModel, reasoningSettings, reasoningItems = null
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
            conversationTurnPersistence.updateAssistantMessageReasoning(assistantToolStarted.id, reasoningItems)
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
                1L, listOf(pendingToolCall), listOf(toolDefinition), any(), any()
            )
        } returns flowOf(ToolCallExecutionEvent.ToolCallCompleted(pendingToolCall.copy(
            output = "{\"results\":[]}",
            status = ToolCallStatus.SUCCESS,
            durationMs = 5L
        )))

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
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        // The follow-up request's context must carry the iteration-1 assistant tool-call message with the
        // reasoning items replayed so the next streaming LLM call sees the prior chain-of-thought.
        assertEquals(2, capturedContexts.size)
        val followUpAssistant = capturedContexts[1].filterIsInstance<RawChatMessage.Assistant>()
            .first { it.toolCalls != null && it.toolCalls.isNotEmpty() }
        assertEquals(reasoningItems, followUpAssistant.reasoningItems)
    }
}
