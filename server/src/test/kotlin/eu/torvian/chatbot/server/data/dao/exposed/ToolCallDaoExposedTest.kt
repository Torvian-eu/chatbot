package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.InsertToolCallError
import eu.torvian.chatbot.server.data.dao.error.ToolCallError
import eu.torvian.chatbot.server.data.dao.error.UpdateToolCallError
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Tests for [ToolCallDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ToolCallDao]:
 * - Getting tool calls by message ID
 * - Getting tool calls by session ID
 * - Getting tool call by ID
 * - Inserting new tool calls
 * - Updating existing tool calls
 * - Deleting tool calls by message ID
 * - Handling error cases (not found, foreign key violations, etc.)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class ToolCallDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var toolCallDao: ToolCallDao
    private lateinit var toolDefinitionDao: ToolDefinitionDao
    private lateinit var sessionDao: SessionDao
    private lateinit var messageDao: MessageDao
    private lateinit var testDataManager: TestDataManager

    // Test fixtures
    private var testSessionId: Long = 0
    private lateinit var testMessage: ChatMessage.AssistantMessage
    private lateinit var testToolDefinition: ToolDefinition

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        toolCallDao = container.get()
        toolDefinitionDao = container.get()
        sessionDao = container.get()
        messageDao = container.get()
        testDataManager = container.get()

        // Create all necessary tables
        testDataManager.createAllTables()

        // Create test session
        testSessionId = sessionDao.insertSession(
            name = "Test Session",
            groupId = null,
            currentModelId = null,
            currentSettingsId = null
        ).getOrElse { throw IllegalStateException("Failed to create test session") }.id

        // Create test assistant message (required for tool calls)
        val userMessage = messageDao.insertMessage(
            sessionId = testSessionId,
            targetMessageId = null,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.USER,
            content = "Search for information",
            modelId = null,
            settingsId = null
        ).getOrElse { throw IllegalStateException("Failed to create user message") }

        testMessage = messageDao.insertMessage(
            sessionId = testSessionId,
            targetMessageId = userMessage.id,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.ASSISTANT,
            content = "Searching...",
            modelId = null,
            settingsId = null
        ).getOrElse { throw IllegalStateException("Failed to create assistant message") } as ChatMessage.AssistantMessage

        // Create test tool definition
        val config = buildJsonObject {
            put("searchEngine", "duckduckgo")
        }
        val inputSchema = buildJsonObject {
            put("type", "object")
        }

        testToolDefinition = toolDefinitionDao.insertToolDefinition(
            name = "web_search",
            description = "Search the web",
            type = ToolType.WEB_SEARCH,
            config = config,
            inputSchema = inputSchema,
            outputSchema = null,
            isEnabled = true
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    // Helper function to create a test tool call
    private suspend fun createTestToolCall(
        messageId: Long = testMessage.id,
        toolDefinitionId: Long? = testToolDefinition.id,
        toolName: String = testToolDefinition.name,
        toolCallId: String? = "call_123",
        status: ToolCallStatus = ToolCallStatus.SUCCESS,
        denialReason: String? = null
    ): ToolCall {
        val input = """{"query":"Kotlin programming"}"""
        val output = """{"results":"Found 10 results"}"""

        return toolCallDao.insertToolCall(
            messageId = messageId,
            toolDefinitionId = toolDefinitionId,
            toolName = toolName,
            toolCallId = toolCallId,
            input = input,
            output = if (status == ToolCallStatus.SUCCESS) output else null,
            status = status,
            errorMessage = if (status == ToolCallStatus.ERROR) "Test error" else null,
            denialReason = denialReason,
            executedAt = Clock.System.now(),
            durationMs = if (status == ToolCallStatus.SUCCESS) 150L else null
        ).getOrElse { throw IllegalStateException("Failed to create test tool call: $it") }
    }

    @Test
    fun `insertToolCall creates tool call record`() = runTest {
        // Setup
        val input = """{"query":"test query"}"""
        val output = """{"result":"test result"}"""
        val now = Clock.System.now()
        val customToolName = "custom_tool"

        // Execute
        val result = toolCallDao.insertToolCall(
            messageId = testMessage.id,
            toolDefinitionId = testToolDefinition.id,
            toolName = customToolName,
            toolCallId = "call_456",
            input = input,
            output = output,
            status = ToolCallStatus.SUCCESS,
            errorMessage = null,
            denialReason = null,
            executedAt = now,
            durationMs = 200L
        )

        // Verify
        val toolCall = result.getOrElse { throw AssertionError("Expected Right but got Left: $it") }
        assertTrue(toolCall.id > 0, "Expected valid ID")
        assertEquals(testMessage.id, toolCall.messageId)
        assertEquals(testToolDefinition.id, toolCall.toolDefinitionId)
        assertEquals(customToolName, toolCall.toolName)
        assertEquals("call_456", toolCall.toolCallId)
        assertEquals(ToolCallStatus.SUCCESS, toolCall.status)
        assertNotNull(toolCall.output)
        assertEquals(200L, toolCall.durationMs)
    }

    @Test
    fun `getToolCallsByMessageId returns all calls for message`() = runTest {
        // Setup: Create multiple tool calls for the same message
        createTestToolCall(messageId = testMessage.id, toolCallId = "call_1", toolName = "tool_one")
        createTestToolCall(messageId = testMessage.id, toolCallId = "call_2", toolName = "tool_two")

        // Execute
        val result = toolCallDao.getToolCallsByMessageId(testMessage.id)

        // Verify
        assertEquals(2, result.size, "Expected 2 tool calls")
        assertTrue(result.any { it.toolCallId == "call_1" }, "Expected call_1")
        assertTrue(result.any { it.toolCallId == "call_2" }, "Expected call_2")
    }

    @Test
    fun `getToolCallsBySessionId returns all calls for session`() = runTest {
        // Setup: Create tool calls for messages in the session
        createTestToolCall(messageId = testMessage.id, toolCallId = "call_1", toolName = "tool_one")

        // Create another message and tool call in the same session
        val message2 = messageDao.insertMessage(
            sessionId = testSessionId,
            targetMessageId = testMessage.id,
            position = MessageInsertPosition.APPEND,
            role = ChatMessage.Role.ASSISTANT,
            content = "Another search",
            modelId = null,
            settingsId = null
        ).getOrElse { throw IllegalStateException("Failed to create second message") } as ChatMessage.AssistantMessage

        createTestToolCall(messageId = message2.id, toolCallId = "call_2", toolName = "tool_two")

        // Execute
        val result = toolCallDao.getToolCallsBySessionId(testSessionId)

        // Verify
        assertEquals(2, result.size, "Expected 2 tool calls")
        assertTrue(result.any { it.toolCallId == "call_1" }, "Expected call_1")
        assertTrue(result.any { it.toolCallId == "call_2" }, "Expected call_2")
    }

    @Test
    fun `getToolCallById returns tool call when exists`() = runTest {
        // Setup
        val created = createTestToolCall(toolCallId = "call_123", toolName = "my_tool")

        // Execute
        val result = toolCallDao.getToolCallById(created.id)

        // Verify
        val toolCall = result.getOrElse { throw AssertionError("Expected Right but got Left: $it") }
        assertEquals(created.id, toolCall.id)
        assertEquals(testMessage.id, toolCall.messageId)
        assertEquals(testToolDefinition.id, toolCall.toolDefinitionId)
        assertEquals("my_tool", toolCall.toolName)
        assertEquals("call_123", toolCall.toolCallId)
    }

    @Test
    fun `getToolCallById returns NotFound when not exists`() = runTest {
        // Execute
        val result = toolCallDao.getToolCallById(999L)

        // Verify
        assertTrue(result.isLeft(), "Expected Left (error)")
        result.onLeft { error ->
            assertTrue(error is ToolCallError.NotFound, "Expected NotFound error")
            assertEquals(999L, (error as ToolCallError.NotFound).id)
        }
    }

    @Test
    fun `toolName is correctly stored and retrieved`() = runTest {
        // Setup: Create a tool call with a specific tool name and null tool definition
        val customToolName = "hallucinated_tool_name"
        val toolCall = createTestToolCall(toolDefinitionId = null, toolName = customToolName)

        // Execute: Retrieve by ID
        val retrieved = toolCallDao.getToolCallById(toolCall.id).getOrElse {
            throw AssertionError("Failed to retrieve tool call")
        }

        // Verify: toolName matches the custom provided name
        assertEquals(customToolName, retrieved.toolName, "toolName should match the directly provided name")
        assertEquals(null, retrieved.toolDefinitionId, "toolDefinitionId should be null for hallucinated tool")

        // Execute: Retrieve by message ID
        val byMessage = toolCallDao.getToolCallsByMessageId(testMessage.id)
        assertEquals(1, byMessage.size)
        assertEquals(customToolName, byMessage.first().toolName)

        // Execute: Retrieve by session ID
        val bySession = toolCallDao.getToolCallsBySessionId(testSessionId)
        assertEquals(1, bySession.size)
        assertEquals(customToolName, bySession.first().toolName)
    }

    @Test
    fun `insertToolCall with invalid messageId returns ForeignKeyViolation error`() = runTest {
        // Setup
        val input = """{"query":"test"}"""

        // Execute
        val result = toolCallDao.insertToolCall(
            messageId = 999L,
            toolDefinitionId = testToolDefinition.id,
            toolName = testToolDefinition.name,
            toolCallId = "call_123",
            input = input,
            output = null,
            status = ToolCallStatus.PENDING,
            errorMessage = null,
            denialReason = null,
            executedAt = Clock.System.now(),
            durationMs = null
        )

        // Verify
        assertTrue(result.isLeft(), "Expected Left (error)")
        result.onLeft { error ->
            assertTrue(
                error is InsertToolCallError.ForeignKeyViolation,
                "Expected ForeignKeyViolation error but got $error"
            )
        }
    }

    @Test
    fun `insertToolCall with invalid toolDefinitionId returns ForeignKeyViolation error`() = runTest {
        // Setup
        val input = """{"query":"test"}"""

        // Execute
        val result = toolCallDao.insertToolCall(
            messageId = testMessage.id,
            toolDefinitionId = 999L,
            toolName = "non_existent_tool",
            toolCallId = "call_123",
            input = input,
            output = null,
            status = ToolCallStatus.PENDING,
            errorMessage = null,
            denialReason = null,
            executedAt = Clock.System.now(),
            durationMs = null
        )

        // Verify
        assertTrue(result.isLeft(), "Expected Left (error)")
        result.onLeft { error ->
            assertTrue(
                error is InsertToolCallError.ForeignKeyViolation,
                "Expected ForeignKeyViolation error but got $error"
            )
        }
    }

    @Test
    fun `updateToolCall updates all fields`() = runTest {
        // Setup: Create initial tool call with PENDING status
        val input = buildJsonObject { put("query", "test") }.toString()
        val initialToolName = "initial_tool"
        val created = toolCallDao.insertToolCall(
            messageId = testMessage.id,
            toolDefinitionId = testToolDefinition.id,
            toolName = initialToolName,
            toolCallId = "call_123",
            input = input,
            output = null,
            status = ToolCallStatus.PENDING,
            errorMessage = null,
            denialReason = null,
            executedAt = Clock.System.now(),
            durationMs = null
        ).getOrElse { throw IllegalStateException("Failed to create tool call") }

        // Execute: Update to SUCCESS with output and new tool name
        val output = buildJsonObject { put("result", "success") }.toString()
        val updatedToolName = "updated_tool"
        val updated = created.copy(
            output = output,
            status = ToolCallStatus.SUCCESS,
            durationMs = 250L,
            toolName = updatedToolName
        )
        val result = toolCallDao.updateToolCall(updated)

        // Verify update succeeded
        assertTrue(result.isRight(), "Expected Right (success)")

        // Verify changes were persisted
        val retrieved = toolCallDao.getToolCallById(created.id).getOrElse {
            throw AssertionError("Failed to retrieve updated tool call")
        }
        assertEquals(ToolCallStatus.SUCCESS, retrieved.status)
        assertNotNull(retrieved.output)
        assertEquals(250L, retrieved.durationMs)
        assertEquals(updatedToolName, retrieved.toolName)
    }

    @Test
    fun `updateToolCall with output and duration`() = runTest {
        // Setup
        val created = createTestToolCall(status = ToolCallStatus.PENDING, toolName = "pending_tool")

        // Execute: Update to add output and duration
        val output = buildJsonObject { put("results", "Found 5 items") }.toString()
        val updated = created.copy(
            output = output,
            status = ToolCallStatus.SUCCESS,
            durationMs = 300L
        )
        val result = toolCallDao.updateToolCall(updated)

        // Verify
        assertTrue(result.isRight(), "Expected Right (success)")
        val retrieved = toolCallDao.getToolCallById(created.id).getOrElse {
            throw AssertionError("Failed to retrieve updated tool call")
        }
        assertEquals(ToolCallStatus.SUCCESS, retrieved.status)
        assertNotNull(retrieved.output)
        assertEquals(300L, retrieved.durationMs)
        assertEquals("pending_tool", retrieved.toolName)
    }

    @Test
    fun `updateToolCall can set nullable fields to null`() = runTest {
        // Setup: Create tool call with error message
        val input = buildJsonObject { put("query", "test") }.toString()
        val created = toolCallDao.insertToolCall(
            messageId = testMessage.id,
            toolDefinitionId = testToolDefinition.id,
            toolName = "error_tool",
            toolCallId = "call_123",
            input = input,
            output = null,
            status = ToolCallStatus.ERROR,
            errorMessage = "Initial error",
            denialReason = null,
            executedAt = Clock.System.now(),
            durationMs = null
        ).getOrElse { throw IllegalStateException("Failed to create tool call") }

        // Execute: Update to clear error message (retry scenario)
        val updated = created.copy(
            status = ToolCallStatus.PENDING,
            errorMessage = null
        )
        val result = toolCallDao.updateToolCall(updated)

        // Verify
        assertTrue(result.isRight(), "Expected Right (success)")
        val retrieved = toolCallDao.getToolCallById(created.id).getOrElse {
            throw AssertionError("Failed to retrieve updated tool call")
        }
        assertEquals(ToolCallStatus.PENDING, retrieved.status)
        assertEquals(null, retrieved.errorMessage, "Expected errorMessage to be null")
        assertEquals("error_tool", retrieved.toolName)
    }

    @Test
    fun `updateToolCall returns NotFound when ID not exists`() = runTest {
        // Setup: Create a tool call object with non-existent ID
        val input = buildJsonObject { put("query", "test") }.toString()
        val nonExistentToolCall = ToolCall(
            id = 999L,
            messageId = testMessage.id,
            toolDefinitionId = testToolDefinition.id,
            toolName = "web_search",
            toolCallId = "call_123",
            input = input,
            output = null,
            status = ToolCallStatus.PENDING,
            errorMessage = null,
            executedAt = Clock.System.now(),
            durationMs = null
        )

        // Execute
        val result = toolCallDao.updateToolCall(nonExistentToolCall)

        // Verify
        assertTrue(result.isLeft(), "Expected Left (error)")
        result.onLeft { error ->
            assertTrue(error is UpdateToolCallError.NotFound, "Expected NotFound error")
            assertEquals(999L, (error as UpdateToolCallError.NotFound).id)
        }
    }

    @Test
    fun `deleteToolCallsByMessageId deletes all calls`() = runTest {
        // Setup: Create multiple tool calls
        createTestToolCall(toolCallId = "call_1", toolName = "delete_tool_1")
        createTestToolCall(toolCallId = "call_2", toolName = "delete_tool_2")

        // Execute
        val result = toolCallDao.deleteToolCallsByMessageId(testMessage.id)

        // Verify deletion succeeded
        assertTrue(result.isRight(), "Expected Right (success)")

        // Verify tool calls are gone
        val remaining = toolCallDao.getToolCallsByMessageId(testMessage.id)
        assertEquals(0, remaining.size, "Expected no tool calls remaining")
    }
}

