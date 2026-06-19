package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.tool.MiscToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.DefaultChatContextBuilder
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
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var llmApiClient: LLMApiClient
    private lateinit var toolCallDao: ToolCallDao
    private lateinit var toolCallOrchestrator: ToolCallOrchestrator
    private lateinit var transactionScope: TransactionScope
    private lateinit var orchestrator: DefaultConversationTurnOrchestrator

    private val baseInstant = Instant.fromEpochMilliseconds(1234567890000L)

    private val testModel = LLMModel(
        id = 1L,
        name = "gpt-4o-mini",
        providerId = 1L,
        active = true,
        displayName = "GPT-4o mini",
        type = LLMModelType.CHAT
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
        messageDao = mockk()
        sessionDao = mockk()
        llmApiClient = mockk()
        toolCallDao = mockk()
        toolCallOrchestrator = mockk()
        transactionScope = mockk()

        orchestrator = DefaultConversationTurnOrchestrator(
            messageDao = messageDao,
            sessionDao = sessionDao,
            llmApiClient = llmApiClient,
            toolCallDao = toolCallDao,
            toolCallOrchestrator = toolCallOrchestrator,
            transactionScope = transactionScope,
            toolResultContentBuilder = DefaultToolResultContentBuilder(),
            chatContextBuilder = DefaultChatContextBuilder(
                fileReferenceContentBuilder = DefaultFileReferenceContentBuilder(),
                toolResultContentBuilder = DefaultToolResultContentBuilder()
            )
        )

        coEvery { transactionScope.transaction(any<suspend () -> Any?>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = invocation.args[0] as suspend () -> Any?
            block()
        }
    }

    /**
     * Clears mocks after each test run.
     */
    @AfterEach
    fun tearDown() {
        clearMocks(messageDao, sessionDao, llmApiClient, toolCallDao, toolCallOrchestrator, transactionScope)
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
            messageDao.insertMessage(testSession.id, null, any(), ChatMessage.Role.USER, "Hello", null, null, any())
        } returns userMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) } returns Unit.right()
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()
        coEvery { toolCallDao.getToolCallsBySessionId(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns completion.right()
        coEvery {
            messageDao.insertMessage(
                testSession.id,
                userMessage.id,
                any(),
                ChatMessage.Role.ASSISTANT,
                assistantMessage.content,
                testModel.id,
                testSettings.id,
                any()
            )
        } returns assistantMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, assistantMessage.id) } returns Unit.right()

        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                content = "Hello",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow()
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
        val toolDefinition = MiscToolDefinition(
            id = 8L,
            name = "search",
            description = "Searches docs",
            type = ToolType.WEB_SEARCH,
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            outputSchema = null,
            isEnabled = true,
            createdAt = baseInstant,
            updatedAt = baseInstant
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
            messageDao.insertMessage(testSession.id, null, any(), ChatMessage.Role.USER, "Find docs", null, null, any())
        } returns userMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) } returns Unit.right()
        coEvery { toolCallDao.getToolCallsBySessionId(testSession.id) } returns emptyList()
        coEvery {
            llmApiClient.completeChat(capture(capturedContexts), any(), any(), any(), any(), any())
        } returnsMany listOf(firstCompletion.right(), secondCompletion.right())
        coEvery {
            messageDao.insertMessage(
                testSession.id,
                userMessage.id,
                any(),
                ChatMessage.Role.ASSISTANT,
                assistantToolMessage.content,
                testModel.id,
                testSettings.id,
                any()
            )
        } returns assistantToolMessage.right()
        coEvery {
            messageDao.insertMessage(
                testSession.id,
                assistantToolMessage.id,
                any(),
                ChatMessage.Role.ASSISTANT,
                assistantFinalMessage.content,
                testModel.id,
                testSettings.id,
                any()
            )
        } returns assistantFinalMessage.right()
        coEvery {
            sessionDao.updateSessionLeafMessageId(testSession.id, assistantToolMessage.id)
        } returns Unit.right()
        coEvery {
            sessionDao.updateSessionLeafMessageId(testSession.id, assistantFinalMessage.id)
        } returns Unit.right()
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()
        coEvery { messageDao.getMessageById(assistantToolMessage.id) } returns assistantToolMessage.right()
        coEvery {
            toolCallDao.insertToolCall(
                assistantToolMessage.id,
                toolDefinition.id,
                toolDefinition.name,
                pendingToolCall.toolCallId,
                pendingToolCall.input,
                null,
                ToolCallStatus.PENDING,
                null,
                null,
                any(),
                null
            )
        } returns pendingToolCall.right()
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(1L, listOf(pendingToolCall), listOf(toolDefinition), any())
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
                toolApprovalFlow = emptyFlow()
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
            messageDao.insertMessage(testSession.id, null, any(), ChatMessage.Role.USER, "Hello", null, null, any())
        } returns userMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, userMessage.id) } returns Unit.right()
        coEvery { toolCallDao.getToolCallsBySessionId(testSession.id) } returns emptyList()
        coEvery {
            messageDao.insertMessage(
                testSession.id,
                userMessage.id,
                any(),
                ChatMessage.Role.ASSISTANT,
                "",
                testModel.id,
                streamingSettings.id,
                any()
            )
        } returns assistantStartedMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(testSession.id, assistantStartedMessage.id) } returns Unit.right()
        coEvery { messageDao.getMessageById(userMessage.id) } returns userMessage.right()
        coEvery {
            llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any())
        } returns flowOf(
            LLMStreamChunk.ContentChunk("Hello ").right(),
            LLMStreamChunk.ContentChunk("there", finishReason = "stop").right(),
            LLMStreamChunk.Done.right()
        )
        coEvery { messageDao.updateMessageContent(assistantStartedMessage.id, "Hello there") } returns assistantFinishedMessage.right()

        val events = orchestrator.processStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, streamingSettings, "api-key"),
                content = "Hello",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow()
            )
        ).toList()

        assertEquals(6, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertIs<ConversationTurnEvent.AssistantMessageStarted>(events[1])
        assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[2])
        assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[3])
        assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[4])
        assertEquals(ConversationTurnEvent.TurnCompleted, events[5])

        coVerify(exactly = 1) { messageDao.updateMessageContent(assistantStartedMessage.id, "Hello there") }
    }
}