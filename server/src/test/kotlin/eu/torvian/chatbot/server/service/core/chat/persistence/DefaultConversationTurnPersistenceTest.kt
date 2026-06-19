package eu.torvian.chatbot.server.service.core.chat.persistence

import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.tool.MiscToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Verifies that [DefaultConversationTurnPersistence] preserves the extracted message and tool-call workflow.
 */
class DefaultConversationTurnPersistenceTest {
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var toolCallDao: ToolCallDao
    private lateinit var transactionScope: TransactionScope
    private lateinit var persistence: DefaultConversationTurnPersistence

    private val baseInstant = Instant.fromEpochMilliseconds(1234567890000L)

    private val testModel = LLMModel(
        id = 1L,
        name = "gpt-4o-mini",
        providerId = 1L,
        active = true,
        displayName = "GPT-4o mini",
        type = LLMModelType.CHAT
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

    /**
     * Recreates the collaborator with fresh mocks for each test.
     */
    @BeforeEach
    fun setUp() {
        messageDao = mockk()
        sessionDao = mockk()
        toolCallDao = mockk()
        transactionScope = mockk()
        persistence = DefaultConversationTurnPersistence(messageDao, sessionDao, toolCallDao, transactionScope)

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
        clearMocks(messageDao, sessionDao, toolCallDao, transactionScope)
    }

    /**
     * Verifies user-message persistence still advances the session leaf and refreshes the parent branch node.
     */
    @Test
    fun `saveUserMessage updates leaf and refreshes parent`() = runTest {
        val savedUserMessage = ChatMessage.UserMessage(
            id = 11L,
            sessionId = 1L,
            content = "Hello",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = 5L,
            childrenMessageIds = emptyList()
        )
        val refreshedParent = ChatMessage.AssistantMessage(
            id = 5L,
            sessionId = 1L,
            content = "Parent",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = listOf(savedUserMessage.id),
            modelId = testModel.id,
            settingsId = testSettings.id
        )

        coEvery { messageDao.insertMessage(1L, 5L, any(), ChatMessage.Role.USER, "Hello", null, null, any()) } returns
            savedUserMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(1L, savedUserMessage.id) } returns Unit.right()
        coEvery { messageDao.getMessageById(5L) } returns refreshedParent.right()

        val result = persistence.saveUserMessage(1L, "Hello", 5L)

        assertEquals(savedUserMessage, result.userMessage)
        assertEquals(refreshedParent, result.updatedParentMessage)
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(1L, savedUserMessage.id) }
        coVerify(exactly = 1) { messageDao.getMessageById(5L) }
    }

    /**
     * Verifies assistant-message persistence still advances the session leaf and reloads the parent.
     */
    @Test
    fun `saveAssistantMessage updates leaf and refreshes parent`() = runTest {
        val savedAssistantMessage = ChatMessage.AssistantMessage(
            id = 12L,
            sessionId = 1L,
            content = "Hi there",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = 5L,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )
        val refreshedParent = ChatMessage.UserMessage(
            id = 5L,
            sessionId = 1L,
            content = "Hello",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = listOf(savedAssistantMessage.id)
        )

        coEvery {
            messageDao.insertMessage(
                1L,
                5L,
                any(),
                ChatMessage.Role.ASSISTANT,
                "Hi there",
                testModel.id,
                testSettings.id,
                any()
            )
        } returns savedAssistantMessage.right()
        coEvery { sessionDao.updateSessionLeafMessageId(1L, savedAssistantMessage.id) } returns Unit.right()
        coEvery { messageDao.getMessageById(5L) } returns refreshedParent.right()

        val result = persistence.saveAssistantMessage(1L, "Hi there", 5L, testModel, testSettings)

        assertEquals(savedAssistantMessage, result.assistantMessage)
        assertEquals(refreshedParent, result.updatedParentMessage)
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(1L, savedAssistantMessage.id) }
        coVerify(exactly = 1) { messageDao.getMessageById(5L) }
    }

    /**
     * Verifies streamed assistant content updates still delegate to the message DAO inside the transaction wrapper.
     */
    @Test
    fun `updateAssistantMessageContent returns updated assistant message`() = runTest {
        val updatedAssistantMessage = ChatMessage.AssistantMessage(
            id = 12L,
            sessionId = 1L,
            content = "Final content",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = 5L,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id
        )

        coEvery { messageDao.updateMessageContent(12L, "Final content") } returns updatedAssistantMessage.right()

        val result = persistence.updateAssistantMessageContent(12L, "Final content")

        assertEquals(updatedAssistantMessage, result)
        coVerify(exactly = 1) { messageDao.updateMessageContent(12L, "Final content") }
    }

    /**
     * Verifies pending tool calls keep the known-tool happy path and the unknown-tool error fallback.
     */
    @Test
    fun `persistPendingToolCalls preserves tool resolution outcomes`() = runTest {
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
        val knownRequest = LLMCompletionResult.CompletionChoice.ToolCallRequest(
            name = "search",
            arguments = "{\"query\":\"docs\"}",
            toolCallId = "call-1"
        )
        val missingRequest = LLMCompletionResult.CompletionChoice.ToolCallRequest(
            name = "missing",
            arguments = "{}",
            toolCallId = "call-2"
        )
        val knownToolCall = ToolCall(
            id = 21L,
            messageId = 12L,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = knownRequest.toolCallId,
            input = knownRequest.arguments,
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )
        val missingToolCall = ToolCall(
            id = 22L,
            messageId = 12L,
            toolDefinitionId = null,
            toolName = missingRequest.name,
            toolCallId = missingRequest.toolCallId,
            input = missingRequest.arguments,
            output = null,
            status = ToolCallStatus.ERROR,
            errorMessage = "Tool 'missing' not found in enabled tools",
            executedAt = baseInstant
        )

        coEvery {
            toolCallDao.insertToolCall(12L, toolDefinition.id, "search", "call-1", "{\"query\":\"docs\"}", null, ToolCallStatus.PENDING, null, null, any(), null)
        } returns knownToolCall.right()
        coEvery {
            toolCallDao.insertToolCall(12L, null, "missing", "call-2", "{}", null, ToolCallStatus.ERROR, "Tool 'missing' not found in enabled tools", null, any(), null)
        } returns missingToolCall.right()

        val result = persistence.persistPendingToolCalls(12L, listOf(knownRequest, missingRequest), listOf(toolDefinition))

        assertEquals(listOf(knownToolCall, missingToolCall), result)
    }

    /**
     * Verifies session tool calls remain sorted by ID for deterministic context reconstruction.
     */
    @Test
    fun `loadSessionToolCalls sorts persisted tool calls by id`() = runTest {
        val newerToolCall = ToolCall(
            id = 22L,
            messageId = 12L,
            toolDefinitionId = 8L,
            toolName = "search",
            toolCallId = "call-2",
            input = "{}",
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )
        val olderToolCall = newerToolCall.copy(id = 21L, toolCallId = "call-1")

        coEvery { toolCallDao.getToolCallsBySessionId(1L) } returns listOf(newerToolCall, olderToolCall)

        val result = persistence.loadSessionToolCalls(1L)

        assertEquals(listOf(21L, 22L), result.map { it.id })
    }
}