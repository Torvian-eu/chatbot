package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.message.DeleteMessageError
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Focused unit tests for the deleteMessageRecursively functionality in [MessageServiceImpl].
 *
 * This test class specifically covers all aspects of message deletion including:
 * - Basic deletion scenarios
 * - Session leaf message update logic
 * - Complex tree structure handling
 * - Error conditions and edge cases
 */
class MessageServiceImplDeleteTest {

    // Mocked dependencies
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var llmModelService: LLMModelService
    private lateinit var modelSettingsService: ModelSettingsService
    private lateinit var llmProviderService: LLMProviderService
    private lateinit var llmApiClient: LLMApiClient
    private lateinit var credentialManager: CredentialManager
    private lateinit var transactionScope: TransactionScope

    // Class under test
    private lateinit var messageService: MessageServiceImpl

    // Common test data
    private val testMessage1 = ChatMessage.UserMessage(
        id = 1L,
        sessionId = 1L,
        content = "Hello, how are you?",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        parentMessageId = null,
        childrenMessageIds = emptyList()
    )

    private val testSession = ChatSession(
        id = 1L,
        name = "Test Session",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        groupId = null,
        currentModelId = 1L,
        currentSettingsId = 1L,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        messageDao = mockk()
        sessionDao = mockk()
        llmModelService = mockk()
        modelSettingsService = mockk()
        llmProviderService = mockk()
        llmApiClient = mockk()
        credentialManager = mockk()
        transactionScope = mockk()

        // Create the service instance with mocked dependencies
        messageService = MessageServiceImpl(
            messageDao, sessionDao, llmModelService, modelSettingsService,
            llmProviderService, llmApiClient, credentialManager, transactionScope
        )

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(
            messageDao, sessionDao, llmModelService, modelSettingsService,
            llmProviderService, llmApiClient, credentialManager, transactionScope
        )
    }

    // === Basic Deletion Tests ===

    @Test
    fun `deleteMessageRecursively should delete message successfully when not in current leaf path`() = runTest {
        // Arrange
        val messageId = 1L
        val sessionId = 10L
        val parentId = 5L
        val messageToDelete = testMessage1.copy(id = messageId, sessionId = sessionId, parentMessageId = parentId)
        val session = testSession.copy(id = sessionId, currentLeafMessageId = 99L) // Different leaf, no update needed
        val allMessages = listOf(messageToDelete)

        coEvery { messageDao.getMessageById(messageId) } returns messageToDelete.right()
        coEvery { messageDao.deleteMessageRecursively(messageId) } returns Unit.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages

        // Act
        val result = messageService.deleteMessageRecursively(messageId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 1) { messageDao.deleteMessageRecursively(messageId) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { messageDao.getMessagesBySessionId(sessionId) }
        // Should not update session since deleted message is not in current leaf path
        coVerify(exactly = 0) { sessionDao.updateSessionLeafMessageId(any(), any()) }
    }

    @Test
    fun `deleteMessageRecursively should return MessageNotFound error when message does not exist`() = runTest {
        // Arrange
        val messageId = 999L
        val daoError = MessageError.MessageNotFound(messageId)
        coEvery { messageDao.getMessageById(messageId) } returns daoError.left()

        // Act
        val result = messageService.deleteMessageRecursively(messageId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent message")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is DeleteMessageError.MessageNotFound, "Should be MessageNotFound error")
        assertEquals(messageId, (error as DeleteMessageError.MessageNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 0) { messageDao.deleteMessageRecursively(any()) }
    }

    @Test
    fun `deleteMessageRecursively should update session leaf when deleting message in current leaf path`() = runTest {
        // Arrange - Create a message tree: root -> child -> grandchild (leaf)
        val sessionId = 10L
        val rootMessageId = 1L
        val childMessageId = 2L
        val grandchildMessageId = 3L

        val rootMessage = testMessage1.copy(
            id = rootMessageId,
            sessionId = sessionId,
            parentMessageId = null,
            childrenMessageIds = listOf(childMessageId)
        )
        val childMessage = testMessage1.copy(
            id = childMessageId,
            sessionId = sessionId,
            parentMessageId = rootMessageId,
            childrenMessageIds = listOf(grandchildMessageId)
        )
        val grandchildMessage = testMessage1.copy(
            id = grandchildMessageId,
            sessionId = sessionId,
            parentMessageId = childMessageId,
            childrenMessageIds = emptyList()
        )

        val session = testSession.copy(id = sessionId, currentLeafMessageId = grandchildMessageId)
        val allMessages = listOf(rootMessage, childMessage, grandchildMessage)

        // Mock getting the message to delete (child message)
        coEvery { messageDao.getMessageById(childMessageId) } returns childMessage.right()
        // Mock getting the session BEFORE deletion
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        // Mock getting all messages BEFORE deletion (this is the key - all messages should be present)
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        // Mock the actual deletion
        coEvery { messageDao.deleteMessageRecursively(childMessageId) } returns Unit.right()
        // The session should be updated to point to the root (parent of deleted message)
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, rootMessageId) } returns Unit.right()

        // Act
        val result = messageService.deleteMessageRecursively(childMessageId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")

        // Verify the session leaf was updated to the parent (root message)
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, rootMessageId) }

        coVerify(exactly = 1) { messageDao.getMessageById(childMessageId) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { messageDao.getMessagesBySessionId(sessionId) }
        coVerify(exactly = 1) { messageDao.deleteMessageRecursively(childMessageId) }
    }

    // === Session State Edge Cases ===

    @Test
    fun `deleteMessageRecursively should update to parent when deleting current leaf message`() = runTest {
        // Arrange - Delete the leaf message itself
        val sessionId = 10L
        val parentMessageId = 1L
        val leafMessageId = 2L

        val parentMessage = testMessage1.copy(
            id = parentMessageId,
            sessionId = sessionId,
            parentMessageId = null,
            childrenMessageIds = listOf(leafMessageId)
        )
        val leafMessage = testMessage1.copy(
            id = leafMessageId,
            sessionId = sessionId,
            parentMessageId = parentMessageId,
            childrenMessageIds = emptyList()
        )

        val session = testSession.copy(id = sessionId, currentLeafMessageId = leafMessageId)
        val allMessages = listOf(parentMessage, leafMessage)

        coEvery { messageDao.getMessageById(leafMessageId) } returns leafMessage.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessageRecursively(leafMessageId) } returns Unit.right()
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, parentMessageId) } returns Unit.right()

        // Act
        val result = messageService.deleteMessageRecursively(leafMessageId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, parentMessageId) }
    }

    // === Root Message Deletion Scenarios ===

    @Test
    fun `deleteMessageRecursively should handle root deletion with multiple roots`() = runTest {
        // Arrange - Delete one root when multiple roots exist
        val sessionId = 10L
        val root1Id = 1L
        val root2Id = 2L
        val leafInRoot2Id = 3L

        val root1 = testMessage1.copy(
            id = root1Id,
            sessionId = sessionId,
            parentMessageId = null,
            childrenMessageIds = emptyList(),
            createdAt = Instant.fromEpochMilliseconds(1000L) // Newer
        )
        val root2 = testMessage1.copy(
            id = root2Id,
            sessionId = sessionId,
            parentMessageId = null,
            childrenMessageIds = listOf(leafInRoot2Id),
            createdAt = Instant.fromEpochMilliseconds(500L) // Older - should be selected
        )
        val leafInRoot2 = testMessage1.copy(
            id = leafInRoot2Id,
            sessionId = sessionId,
            parentMessageId = root2Id,
            childrenMessageIds = emptyList()
        )

        val session = testSession.copy(id = sessionId, currentLeafMessageId = root1Id) // Current leaf is root1
        val allMessages = listOf(root1, root2, leafInRoot2)

        coEvery { messageDao.getMessageById(root1Id) } returns root1.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessageRecursively(root1Id) } returns Unit.right()
        // Should update to leaf of oldest remaining root (root2 -> leafInRoot2)
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, leafInRoot2Id) } returns Unit.right()

        // Act
        val result = messageService.deleteMessageRecursively(root1Id)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, leafInRoot2Id) }
    }

    @Test
    fun `deleteMessageRecursively should set session leaf to null when deleting last root`() = runTest {
        // Arrange - Delete the only root message
        val sessionId = 10L
        val rootId = 1L

        val rootMessage = testMessage1.copy(
            id = rootId,
            sessionId = sessionId,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )

        val session = testSession.copy(id = sessionId, currentLeafMessageId = rootId)
        val allMessages = listOf(rootMessage)

        coEvery { messageDao.getMessageById(rootId) } returns rootMessage.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessageRecursively(rootId) } returns Unit.right()
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, null) } returns Unit.right()

        // Act
        val result = messageService.deleteMessageRecursively(rootId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, null) }
    }

    // === Complex Tree Structure Tests ===

    @Test
    fun `deleteMessageRecursively should update to sibling's leaf when parent has remaining children`() = runTest {
        // Arrange - Delete middle child when parent has multiple children
        val sessionId = 10L
        val parentId = 1L
        val child1Id = 2L // Will be deleted
        val child2Id = 3L // Remaining sibling
        val grandchildId = 4L // Leaf in child2's subtree
        val leafId = 5L // Current leaf in child1's subtree

        val parent = testMessage1.copy(
            id = parentId,
            sessionId = sessionId,
            parentMessageId = null,
            childrenMessageIds = listOf(child1Id, child2Id)
        )
        val child1 = testMessage1.copy(
            id = child1Id,
            sessionId = sessionId,
            parentMessageId = parentId,
            childrenMessageIds = listOf(leafId)
        )
        val child2 = testMessage1.copy(
            id = child2Id,
            sessionId = sessionId,
            parentMessageId = parentId,
            childrenMessageIds = listOf(grandchildId)
        )
        val grandchild = testMessage1.copy(
            id = grandchildId,
            sessionId = sessionId,
            parentMessageId = child2Id,
            childrenMessageIds = emptyList()
        )
        val currentLeaf = testMessage1.copy(
            id = leafId,
            sessionId = sessionId,
            parentMessageId = child1Id,
            childrenMessageIds = emptyList()
        )

        val session = testSession.copy(id = sessionId, currentLeafMessageId = leafId)
        val allMessages = listOf(parent, child1, child2, grandchild, currentLeaf)

        coEvery { messageDao.getMessageById(child1Id) } returns child1.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessageRecursively(child1Id) } returns Unit.right()
        // Should update to leaf of first remaining sibling (child2 -> grandchild)
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, grandchildId) } returns Unit.right()

        // Act
        val result = messageService.deleteMessageRecursively(child1Id)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, grandchildId) }
    }

    @Test
    fun `deleteMessageRecursively should not update session when deleted message is not in current leaf path`() = runTest {
        // Arrange - Create two separate branches, delete from non-current branch
        val sessionId = 10L
        val rootId = 1L
        val branch1Id = 2L // Current leaf path
        val branch2Id = 3L // Different branch - will be deleted

        val root = testMessage1.copy(
            id = rootId,
            sessionId = sessionId,
            parentMessageId = null,
            childrenMessageIds = listOf(branch1Id, branch2Id)
        )
        val branch1 = testMessage1.copy(
            id = branch1Id,
            sessionId = sessionId,
            parentMessageId = rootId,
            childrenMessageIds = emptyList()
        )
        val branch2 = testMessage1.copy(
            id = branch2Id,
            sessionId = sessionId,
            parentMessageId = rootId,
            childrenMessageIds = emptyList()
        )

        val session = testSession.copy(id = sessionId, currentLeafMessageId = branch1Id) // Current leaf is branch1
        val allMessages = listOf(root, branch1, branch2)

        coEvery { messageDao.getMessageById(branch2Id) } returns branch2.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessageRecursively(branch2Id) } returns Unit.right()

        // Act
        val result = messageService.deleteMessageRecursively(branch2Id)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { messageDao.getMessageById(branch2Id) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { messageDao.getMessagesBySessionId(sessionId) }
        coVerify(exactly = 1) { messageDao.deleteMessageRecursively(branch2Id) }
        // Should NOT update session since branch2 is not in branch1's path
        coVerify(exactly = 0) { sessionDao.updateSessionLeafMessageId(any(), any()) }
    }

    // === Error Handling Tests ===

    @Test
    fun `deleteMessageRecursively should return SessionUpdateFailed when session retrieval fails`() = runTest {
        // Arrange
        val messageId = 1L
        val sessionId = 10L
        val messageToDelete = testMessage1.copy(id = messageId, sessionId = sessionId)
        val sessionError = SessionError.SessionNotFound(sessionId)

        coEvery { messageDao.getMessageById(messageId) } returns messageToDelete.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns sessionError.left()

        // Act
        val result = messageService.deleteMessageRecursively(messageId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for session retrieval failure")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is DeleteMessageError.SessionUpdateFailed, "Should be SessionUpdateFailed error")
        assertEquals(sessionId, (error as DeleteMessageError.SessionUpdateFailed).sessionId)

        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 0) { messageDao.deleteMessageRecursively(any()) }
    }

    @Test
    fun `deleteMessageRecursively should return SessionUpdateFailed when session leaf update fails`() = runTest {
        // Arrange
        val sessionId = 10L
        val messageId = 1L
        val parentId = 2L

        val messageToDelete = testMessage1.copy(
            id = messageId,
            sessionId = sessionId,
            parentMessageId = parentId,
            childrenMessageIds = emptyList()
        )
        val parent = testMessage1.copy(
            id = parentId,
            sessionId = sessionId,
            parentMessageId = null,
            childrenMessageIds = listOf(messageId)
        )

        val session = testSession.copy(id = sessionId, currentLeafMessageId = messageId)
        val allMessages = listOf(parent, messageToDelete)
        val sessionUpdateError = SessionError.SessionNotFound(sessionId)

        coEvery { messageDao.getMessageById(messageId) } returns messageToDelete.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessageRecursively(messageId) } returns Unit.right()
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, parentId) } returns sessionUpdateError.left()

        // Act
        val result = messageService.deleteMessageRecursively(messageId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for session update failure")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is DeleteMessageError.SessionUpdateFailed, "Should be SessionUpdateFailed error")
        assertEquals(sessionId, (error as DeleteMessageError.SessionUpdateFailed).sessionId)

        coVerify(exactly = 1) { messageDao.deleteMessageRecursively(messageId) }
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, parentId) }
    }

    @Test
    fun `deleteMessageRecursively should handle deletion failure after successful session checks`() = runTest {
        // Arrange
        val messageId = 1L
        val sessionId = 10L
        val messageToDelete = testMessage1.copy(id = messageId, sessionId = sessionId)
        val session = testSession.copy(id = sessionId, currentLeafMessageId = null)
        val deleteError = MessageError.MessageNotFound(messageId)

        coEvery { messageDao.getMessageById(messageId) } returns messageToDelete.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.deleteMessageRecursively(messageId) } returns deleteError.left()

        // Act
        val result = messageService.deleteMessageRecursively(messageId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for deletion failure")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is DeleteMessageError.MessageNotFound, "Should be MessageNotFound error")
        assertEquals(messageId, (error as DeleteMessageError.MessageNotFound).id)

        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { messageDao.deleteMessageRecursively(messageId) }
        coVerify(exactly = 0) { sessionDao.updateSessionLeafMessageId(any(), any()) }
    }
}
