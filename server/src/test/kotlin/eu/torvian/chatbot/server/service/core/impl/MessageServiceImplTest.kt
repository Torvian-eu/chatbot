package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.MessageError
import eu.torvian.chatbot.server.service.core.error.message.UpdateMessageContentError
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [MessageServiceImpl].
 *
 * This test suite verifies that [MessageServiceImpl] correctly orchestrates
 * calls to the underlying DAOs and services, and handles business logic validation.
 * All dependencies are mocked using MockK.
 */
class MessageServiceImplTest {

    // Mocked dependencies
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var transactionScope: TransactionScope

    // Class under test
    private lateinit var messageService: MessageServiceImpl

    // Test data
    private val testMessage1 = ChatMessage.UserMessage(
        id = 1L,
        sessionId = 1L,
        content = "Hello, how are you?",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        parentMessageId = null,
        childrenMessageIds = emptyList()
    )

    private val testMessage2 = ChatMessage.AssistantMessage(
        id = 2L,
        sessionId = 1L,
        content = "I'm doing well, thank you!",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        parentMessageId = 1L,
        childrenMessageIds = emptyList(),
        fileReferences = emptyList(),
        modelId = 1L,
        settingsId = 1L
    )


    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        messageDao = mockk()
        sessionDao = mockk()
        transactionScope = mockk()

        // Create the service instance with mocked dependencies
        messageService = MessageServiceImpl(
            messageDao, sessionDao, transactionScope
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
            messageDao, sessionDao, transactionScope
        )
    }

    // --- getMessagesBySessionId Tests ---

    @Test
    fun `getMessagesBySessionId should return list of messages from DAO`() = runTest {
        // Arrange
        val sessionId = 1L
        val expectedMessages = listOf(testMessage1, testMessage2)
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns expectedMessages

        // Act
        val result = messageService.getMessagesBySessionId(sessionId)

        // Assert
        assertEquals(expectedMessages, result, "Should return the messages from DAO")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { messageDao.getMessagesBySessionId(sessionId) }
    }

    @Test
    fun `getMessagesBySessionId should return empty list when no messages exist`() = runTest {
        // Arrange
        val sessionId = 1L
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns emptyList()

        // Act
        val result = messageService.getMessagesBySessionId(sessionId)

        // Assert
        assertTrue(result.isEmpty(), "Should return empty list when no messages exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { messageDao.getMessagesBySessionId(sessionId) }
    }

    // --- updateMessageContent Tests ---

    @Test
    fun `updateMessageContent should update message content successfully`() = runTest {
        // Arrange
        val messageId = 1L
        val newContent = "Updated content"
        val updatedMessage = testMessage1.copy(content = newContent)
        coEvery { messageDao.updateMessageContent(messageId, newContent) } returns updatedMessage.right()

        // Act
        val result = messageService.updateMessageContent(messageId, newContent)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        assertEquals(updatedMessage, result.getOrNull(), "Should return the updated message")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { messageDao.updateMessageContent(messageId, newContent) }
    }

    @Test
    fun `updateMessageContent should return MessageNotFound error when message does not exist`() = runTest {
        // Arrange
        val messageId = 999L
        val newContent = "Updated content"
        val daoError = MessageError.MessageNotFound(messageId)
        coEvery { messageDao.updateMessageContent(messageId, newContent) } returns daoError.left()

        // Act
        val result = messageService.updateMessageContent(messageId, newContent)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent message")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateMessageContentError.MessageNotFound>(error, "Should be MessageNotFound error")
        assertEquals(messageId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { messageDao.updateMessageContent(messageId, newContent) }
    }
}
