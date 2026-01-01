package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.SessionOwnershipDao
import eu.torvian.chatbot.server.data.dao.SessionToolConfigDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SessionServiceImpl.cloneSession].
 *
 * This test suite verifies that the clone session functionality correctly:
 * - Clones sessions with all their data (messages, tool calls, tool configs)
 * - Preserves message timestamps and thread structure
 * - Maps currentLeafMessageId correctly
 * - Handles authorization properly
 * - Validates input correctly
 */
class SessionServiceImplCloneTest {

    // Mocked dependencies
    private lateinit var sessionDao: SessionDao
    private lateinit var sessionOwnershipDao: SessionOwnershipDao
    private lateinit var settingsDao: SettingsDao
    private lateinit var modelDao: ModelDao
    private lateinit var messageDao: MessageDao
    private lateinit var toolCallDao: ToolCallDao
    private lateinit var sessionToolConfigDao: SessionToolConfigDao
    private lateinit var transactionScope: TransactionScope

    // Class under test
    private lateinit var sessionService: SessionServiceImpl

    // Test data
    private val testUserId = 1L
    private val testSessionId = 100L
    private val testClonedSessionId = 200L
    private val testGroupId = 10L
    private val testModelId = 20L
    private val testSettingsId = 30L

    private val testTimestamp1 = Instant.fromEpochMilliseconds(1000000000000L)
    private val testTimestamp2 = Instant.fromEpochMilliseconds(1000001000000L)
    private val testTimestamp3 = Instant.fromEpochMilliseconds(1000002000000L)

    private val originalSession = ChatSession(
        id = testSessionId,
        name = "Original Session",
        createdAt = testTimestamp1,
        updatedAt = testTimestamp1,
        groupId = testGroupId,
        currentModelId = testModelId,
        currentSettingsId = testSettingsId,
        currentLeafMessageId = 103L,
        messages = emptyList()
    )

    private val clonedSession = ChatSession(
        id = testClonedSessionId,
        name = "Cloned Session",
        createdAt = testTimestamp1,
        updatedAt = testTimestamp1,
        groupId = testGroupId,
        currentModelId = testModelId,
        currentSettingsId = testSettingsId,
        currentLeafMessageId = 203L, // Mapped from 103L
        messages = emptyList()
    )

    // Test messages with threading structure
    private val originalMessage1 = ChatMessage.UserMessage(
        id = 101L,
        sessionId = testSessionId,
        content = "Root message 1",
        createdAt = testTimestamp1,
        updatedAt = testTimestamp1,
        parentMessageId = null,
        childrenMessageIds = listOf(102L)
    )

    private val originalMessage2 = ChatMessage.AssistantMessage(
        id = 102L,
        sessionId = testSessionId,
        content = "Child of message 1",
        createdAt = testTimestamp2,
        updatedAt = testTimestamp2,
        parentMessageId = 101L,
        childrenMessageIds = listOf(103L),
        fileReferences = emptyList(),
        modelId = testModelId,
        settingsId = testSettingsId
    )

    private val originalMessage3 = ChatMessage.UserMessage(
        id = 103L,
        sessionId = testSessionId,
        content = "Grandchild message",
        createdAt = testTimestamp3,
        updatedAt = testTimestamp3,
        parentMessageId = 102L,
        childrenMessageIds = emptyList()
    )

    private val originalMessages = listOf(originalMessage1, originalMessage2, originalMessage3)

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        sessionDao = mockk()
        sessionOwnershipDao = mockk()
        settingsDao = mockk()
        modelDao = mockk()
        messageDao = mockk()
        toolCallDao = mockk()
        sessionToolConfigDao = mockk()
        transactionScope = mockk()

        // Create the service instance with mocked dependencies
        sessionService = SessionServiceImpl(
            sessionDao,
            sessionOwnershipDao,
            settingsDao,
            modelDao,
            messageDao,
            toolCallDao,
            sessionToolConfigDao,
            transactionScope
        )

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        clearMocks(
            sessionDao,
            sessionOwnershipDao,
            settingsDao,
            modelDao,
            messageDao,
            toolCallDao,
            sessionToolConfigDao,
            transactionScope
        )
    }

    // --- Successful Clone Tests ---

    @Test
    fun `cloneSession should successfully clone session with all data`() = runTest {
        // Arrange
        val cloneName = "Cloned Session"
        coEvery { sessionDao.getSessionById(testSessionId) } returns originalSession.right()
        coEvery { sessionOwnershipDao.getOwner(testSessionId) } returns testUserId.right()
        coEvery { sessionDao.insertSession(cloneName, testGroupId, testModelId, testSettingsId) } returns clonedSession.copy(
            name = cloneName,
            currentLeafMessageId = null
        ).right()
        coEvery { sessionOwnershipDao.setOwner(testClonedSessionId, testUserId) } returns Unit.right()
        coEvery { messageDao.getMessagesBySessionId(testSessionId) } returns originalMessages
        coEvery { toolCallDao.getToolCallsBySessionId(testSessionId) } returns emptyList()
        coEvery { sessionToolConfigDao.getEnabledToolsForSession(testSessionId) } returns emptyList()

        // Mock message insertions - need to capture and return cloned messages with new IDs
        var nextMessageId = 201L
        coEvery {
            messageDao.insertMessage(
                sessionId = testClonedSessionId,
                targetMessageId = any(),
                position = any(),
                role = any(),
                content = any(),
                modelId = any(),
                settingsId = any(),
                fileReferences = any(),
                createdAt = any(),
                updatedAt = any()
            )
        } answers {
            val role = arg<ChatMessage.Role>(3)
            val content = arg<String>(4)
            val modelId = arg<Long?>(5)
            val settingsId = arg<Long?>(6)
            val createdAt = arg<Instant>(8)
            val updatedAt = arg<Instant>(9)
            val parentMessageId = arg<Long?>(1)
            val messageId = nextMessageId++

            if (role == ChatMessage.Role.ASSISTANT) {
                ChatMessage.AssistantMessage(
                    id = messageId,
                    sessionId = testClonedSessionId,
                    content = content,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    parentMessageId = parentMessageId,
                    childrenMessageIds = emptyList(),
                    fileReferences = emptyList(),
                    modelId = modelId,
                    settingsId = settingsId
                )
            } else {
                ChatMessage.UserMessage(
                    id = messageId,
                    sessionId = testClonedSessionId,
                    content = content,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    parentMessageId = parentMessageId,
                    childrenMessageIds = emptyList()
                )
            }.right()
        }

        coEvery { sessionDao.updateSessionLeafMessageId(testClonedSessionId, 203L) } returns Unit.right()
        coEvery { sessionDao.getSessionById(testClonedSessionId) } returns clonedSession.right()

        // Act
        val result = sessionService.cloneSession(testSessionId, cloneName)

        // Assert
        assertTrue(result.isRight())
        val cloned = result.getOrNull()
        assertNotNull(cloned)
        assertEquals(testClonedSessionId, cloned.id)
        assertEquals(cloneName, cloned.name)
        assertEquals(testGroupId, cloned.groupId)
        assertEquals(testModelId, cloned.currentModelId)
        assertEquals(testSettingsId, cloned.currentSettingsId)
        assertEquals(203L, cloned.currentLeafMessageId)

        // Verify all DAOs were called correctly
        coVerify { sessionDao.getSessionById(testSessionId) }
        coVerify { sessionOwnershipDao.getOwner(testSessionId) }
        coVerify { sessionDao.insertSession(cloneName, testGroupId, testModelId, testSettingsId) }
        coVerify { sessionOwnershipDao.setOwner(testClonedSessionId, testUserId) }
        coVerify { messageDao.getMessagesBySessionId(testSessionId) }
        coVerify(exactly = 3) { messageDao.insertMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify { sessionDao.updateSessionLeafMessageId(testClonedSessionId, 203L) }
        coVerify { toolCallDao.getToolCallsBySessionId(testSessionId) }
        coVerify { sessionToolConfigDao.getEnabledToolsForSession(testSessionId) }
        coVerify { sessionDao.getSessionById(testClonedSessionId) }
    }

    @Test
    fun `cloneSession should preserve message timestamps`() = runTest {
        // Arrange
        val cloneName = "Cloned Session"
        coEvery { sessionDao.getSessionById(testSessionId) } returns originalSession.right()
        coEvery { sessionOwnershipDao.getOwner(testSessionId) } returns testUserId.right()
        coEvery { sessionDao.insertSession(any(), any(), any(), any()) } returns clonedSession.copy(
            name = cloneName,
            currentLeafMessageId = null
        ).right()
        coEvery { sessionOwnershipDao.setOwner(any(), any()) } returns Unit.right()
        coEvery { messageDao.getMessagesBySessionId(testSessionId) } returns originalMessages
        coEvery { toolCallDao.getToolCallsBySessionId(testSessionId) } returns emptyList()
        coEvery { sessionToolConfigDao.getEnabledToolsForSession(testSessionId) } returns emptyList()

        var nextMessageId = 201L
        coEvery {
            messageDao.insertMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val createdAt = arg<Instant>(8)
            val updatedAt = arg<Instant>(9)
            ChatMessage.UserMessage(
                id = nextMessageId++,
                sessionId = testClonedSessionId,
                content = "Test",
                createdAt = createdAt,
                updatedAt = updatedAt,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            ).right()
        }

        coEvery { sessionDao.updateSessionLeafMessageId(any(), any()) } returns Unit.right()
        coEvery { sessionDao.getSessionById(testClonedSessionId) } returns clonedSession.right()

        // Act
        sessionService.cloneSession(testSessionId, cloneName)

        // Assert - verify that insertMessage was called with original timestamps
        coVerify { messageDao.insertMessage(any(), any(), any(), any(), any(), any(), any(), any(), testTimestamp1, testTimestamp1) }
        coVerify { messageDao.insertMessage(any(), any(), any(), any(), any(), any(), any(), any(), testTimestamp2, testTimestamp2) }
        coVerify { messageDao.insertMessage(any(), any(), any(), any(), any(), any(), any(), any(), testTimestamp3, testTimestamp3) }
    }

    @Test
    fun `cloneSession should clone messages in correct order preserving tree structure`() = runTest {
        // Arrange
        val cloneName = "Cloned Session"
        coEvery { sessionDao.getSessionById(testSessionId) } returns originalSession.right()
        coEvery { sessionOwnershipDao.getOwner(testSessionId) } returns testUserId.right()
        coEvery { sessionDao.insertSession(any(), any(), any(), any()) } returns clonedSession.copy(
            name = cloneName,
            currentLeafMessageId = null
        ).right()
        coEvery { sessionOwnershipDao.setOwner(any(), any()) } returns Unit.right()
        coEvery { messageDao.getMessagesBySessionId(testSessionId) } returns originalMessages
        coEvery { toolCallDao.getToolCallsBySessionId(testSessionId) } returns emptyList()
        coEvery { sessionToolConfigDao.getEnabledToolsForSession(testSessionId) } returns emptyList()

        val insertedMessages = mutableListOf<Pair<Long?, String>>() // parentId, content
        var nextMessageId = 201L
        coEvery {
            messageDao.insertMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val parentId = arg<Long?>(1)
            val content = arg<String>(4)
            insertedMessages.add(parentId to content)

            ChatMessage.UserMessage(
                id = nextMessageId++,
                sessionId = testClonedSessionId,
                content = content,
                createdAt = testTimestamp1,
                updatedAt = testTimestamp1,
                parentMessageId = parentId,
                childrenMessageIds = emptyList()
            ).right()
        }

        coEvery { sessionDao.updateSessionLeafMessageId(any(), any()) } returns Unit.right()
        coEvery { sessionDao.getSessionById(testClonedSessionId) } returns clonedSession.right()

        // Act
        sessionService.cloneSession(testSessionId, cloneName)

        // Assert - verify messages were inserted in correct order
        assertEquals(3, insertedMessages.size)

        // First message (root) should have no parent
        assertEquals(null to "Root message 1", insertedMessages[0])

        // Second message should have parent 201 (cloned ID of first message)
        assertEquals(201L to "Child of message 1", insertedMessages[1])

        // Third message should have parent 202 (cloned ID of second message)
        assertEquals(202L to "Grandchild message", insertedMessages[2])
    }

    @Test
    fun `cloneSession should clone tool calls with mapped message IDs`() = runTest {
        // Arrange
        val cloneName = "Cloned Session"
        val originalToolCall = ToolCall(
            id = 1001L,
            messageId = 102L, // Links to originalMessage2
            toolDefinitionId = 50L,
            toolName = "test_tool",
            toolCallId = "tc_123",
            input = """{"arg": "value"}""",
            output = """{"result": "success"}""",
            status = ToolCallStatus.SUCCESS,
            errorMessage = null,
            denialReason = null,
            executedAt = testTimestamp2,
            durationMs = 100L
        )

        coEvery { sessionDao.getSessionById(testSessionId) } returns originalSession.right()
        coEvery { sessionOwnershipDao.getOwner(testSessionId) } returns testUserId.right()
        coEvery { sessionDao.insertSession(any(), any(), any(), any()) } returns clonedSession.copy(
            name = cloneName,
            currentLeafMessageId = null
        ).right()
        coEvery { sessionOwnershipDao.setOwner(any(), any()) } returns Unit.right()
        coEvery { messageDao.getMessagesBySessionId(testSessionId) } returns originalMessages
        coEvery { toolCallDao.getToolCallsBySessionId(testSessionId) } returns listOf(originalToolCall)
        coEvery { sessionToolConfigDao.getEnabledToolsForSession(testSessionId) } returns emptyList()

        var nextMessageId = 201L
        coEvery {
            messageDao.insertMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            ChatMessage.UserMessage(
                id = nextMessageId++,
                sessionId = testClonedSessionId,
                content = "Test",
                createdAt = testTimestamp1,
                updatedAt = testTimestamp1,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            ).right()
        }

        val toolCallMessageIdSlot = slot<Long>()
        coEvery {
            toolCallDao.insertToolCall(
                messageId = capture(toolCallMessageIdSlot),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns originalToolCall.copy(id = 2001L, messageId = 202L).right()

        coEvery { sessionDao.updateSessionLeafMessageId(any(), any()) } returns Unit.right()
        coEvery { sessionDao.getSessionById(testClonedSessionId) } returns clonedSession.right()

        // Act
        sessionService.cloneSession(testSessionId, cloneName)

        // Assert - verify tool call was cloned with mapped message ID
        coVerify { toolCallDao.insertToolCall(202L, any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(202L, toolCallMessageIdSlot.captured) // 102L -> 202L mapping
    }

    @Test
    fun `cloneSession should clone session tool configurations`() = runTest {
        // Arrange
        val cloneName = "Cloned Session"
        val enabledTool = mockk<ToolDefinition> {
            coEvery { id } returns 50L
        }

        coEvery { sessionDao.getSessionById(testSessionId) } returns originalSession.right()
        coEvery { sessionOwnershipDao.getOwner(testSessionId) } returns testUserId.right()
        coEvery { sessionDao.insertSession(any(), any(), any(), any()) } returns clonedSession.copy(
            name = cloneName,
            currentLeafMessageId = null
        ).right()
        coEvery { sessionOwnershipDao.setOwner(any(), any()) } returns Unit.right()
        coEvery { messageDao.getMessagesBySessionId(testSessionId) } returns originalMessages
        coEvery { toolCallDao.getToolCallsBySessionId(testSessionId) } returns emptyList()
        coEvery { sessionToolConfigDao.getEnabledToolsForSession(testSessionId) } returns listOf(enabledTool)

        var nextMessageId = 201L
        coEvery {
            messageDao.insertMessage(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            ChatMessage.UserMessage(
                id = nextMessageId++,
                sessionId = testClonedSessionId,
                content = "Test",
                createdAt = testTimestamp1,
                updatedAt = testTimestamp1,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            ).right()
        }

        coEvery {
            sessionToolConfigDao.setToolsEnabledForSession(testClonedSessionId, listOf(50L), true)
        } returns Unit.right()

        coEvery { sessionDao.updateSessionLeafMessageId(any(), any()) } returns Unit.right()
        coEvery { sessionDao.getSessionById(testClonedSessionId) } returns clonedSession.right()

        // Act
        sessionService.cloneSession(testSessionId, cloneName)

        // Assert
        coVerify { sessionToolConfigDao.getEnabledToolsForSession(testSessionId) }
        coVerify { sessionToolConfigDao.setToolsEnabledForSession(testClonedSessionId, listOf(50L), true) }
    }

    // --- Error Tests ---

    @Test
    fun `cloneSession should return InvalidName error when name is blank`() = runTest {
        // Act
        val result = sessionService.cloneSession(testSessionId, "   ")

        // Assert
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<CloneSessionError.InvalidName>(error)
        assertEquals("Session name cannot be blank.", (error as CloneSessionError.InvalidName).reason)

        // Verify no DAO calls were made
        coVerify(exactly = 0) { sessionDao.getSessionById(any()) }
    }

    @Test
    fun `cloneSession should return SessionNotFound when session does not exist`() = runTest {
        // Arrange
        coEvery { sessionDao.getSessionById(testSessionId) } returns SessionError.SessionNotFound(testSessionId).left()

        // Act
        val result = sessionService.cloneSession(testSessionId, "Cloned Session")

        // Assert
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<CloneSessionError.SessionNotFound>(error)
        assertEquals(testSessionId, (error as CloneSessionError.SessionNotFound).id)
    }

    @Test
    fun `cloneSession should return InternalError when getting owner fails`() = runTest {
        // Arrange
        coEvery { sessionDao.getSessionById(testSessionId) } returns originalSession.right()
        coEvery { sessionOwnershipDao.getOwner(testSessionId) } returns GetOwnerError.ResourceNotFound("session").left()

        // Act
        val result = sessionService.cloneSession(testSessionId, "Cloned Session")

        // Assert
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<CloneSessionError.InternalError>(error)
        assertTrue((error as CloneSessionError.InternalError).message.contains("Failed to get session ownership"))
    }

    @Test
    fun `cloneSession should return InternalError when session creation fails`() = runTest {
        // Arrange
        coEvery { sessionDao.getSessionById(testSessionId) } returns originalSession.right()
        coEvery { sessionOwnershipDao.getOwner(testSessionId) } returns testUserId.right()
        coEvery { sessionDao.insertSession(any(), any(), any(), any()) } returns
            SessionError.ForeignKeyViolation("Invalid groupId").left()

        // Act
        val result = sessionService.cloneSession(testSessionId, "Cloned Session")

        // Assert
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<CloneSessionError.InternalError>(error)
        assertTrue((error as CloneSessionError.InternalError).message.contains("Failed to create cloned session"))
    }

    @Test
    fun `cloneSession should return InternalError when setting ownership fails`() = runTest {
        // Arrange
        coEvery { sessionDao.getSessionById(testSessionId) } returns originalSession.right()
        coEvery { sessionOwnershipDao.getOwner(testSessionId) } returns testUserId.right()
        coEvery { sessionDao.insertSession(any(), any(), any(), any()) } returns clonedSession.copy(
            currentLeafMessageId = null
        ).right()
        coEvery { sessionOwnershipDao.setOwner(testClonedSessionId, testUserId) } returns
            SetOwnerError.ForeignKeyViolation("session", testUserId).left()

        // Act
        val result = sessionService.cloneSession(testSessionId, "Cloned Session")

        // Assert
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<CloneSessionError.InternalError>(error)
        assertTrue((error as CloneSessionError.InternalError).message.contains("Failed to set session ownership"))
    }
}

