package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.server.data.dao.MessageDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.SessionOwnershipDao
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
import kotlin.test.assertTrue

/**
 * Tests for deleteMessage (non-recursive single delete) in [MessageServiceImpl].
 * Mirrors the structure of MessageServiceImplDeleteTest but targets single-delete semantics.
 */
class MessageServiceImplSingleDeleteTest {

    // Mocks
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var sessionOwnershipDao: SessionOwnershipDao
    private lateinit var llmModelService: LLMModelService
    private lateinit var modelSettingsService: ModelSettingsService
    private lateinit var llmProviderService: LLMProviderService
    private lateinit var llmApiClient: LLMApiClient
    private lateinit var credentialManager: CredentialManager
    private lateinit var transactionScope: TransactionScope

    // SUT
    private lateinit var messageService: MessageServiceImpl

    // Shared fixtures
    private val testMessage1 = ChatMessage.UserMessage(
        id = 1L,
        sessionId = 1L,
        content = "Hello",
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
        messageDao = mockk()
        sessionDao = mockk()
        sessionOwnershipDao = mockk()
        llmModelService = mockk()
        modelSettingsService = mockk()
        llmProviderService = mockk()
        llmApiClient = mockk()
        credentialManager = mockk()
        transactionScope = mockk()

        messageService = MessageServiceImpl(
            messageDao, sessionDao, sessionOwnershipDao, llmModelService, modelSettingsService,
            llmProviderService, llmApiClient, credentialManager, transactionScope
        )

        // Execute transaction blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        clearMocks(
            messageDao, sessionDao, sessionOwnershipDao, llmModelService, modelSettingsService,
            llmProviderService, llmApiClient, credentialManager, transactionScope
        )
    }

    // === Basic ===
    @Test
    fun `deleteMessage should delete message successfully when not in current leaf path`() = runTest {
        // Arrange - Single delete of a message not in the current leaf path
        val userId = 1L
        val sessionId = 10L
        val messageId = 5L
        val messageToDelete = ChatMessage.UserMessage(
            id = messageId,
            sessionId = sessionId,
            content = "To be deleted",
            createdAt = Instant.fromEpochMilliseconds(1234567890000L),
            updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val session = ChatSession(
            id = sessionId,
            name = "Test Session",
            createdAt = Instant.fromEpochMilliseconds(1234567890000L),
            updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
            groupId = null,
            currentModelId = 1L,
            currentSettingsId = 1L,
            currentLeafMessageId = 99L, // Different leaf message - no update needed
            messages = emptyList()
        )
        val allMessages = listOf(messageToDelete)

        coEvery { messageDao.getMessageById(messageId) } returns messageToDelete.right()
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessage(messageId) } returns Unit.right()

        val result = messageService.deleteMessage(userId, messageId)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { messageDao.getMessagesBySessionId(sessionId) }
        coVerify(exactly = 1) { messageDao.deleteMessage(messageId) }
        coVerify(exactly = 0) { sessionDao.updateSessionLeafMessageId(any(), any()) }
        // Ensure recursive delete is NOT used
        coVerify(exactly = 0) { messageDao.deleteMessageRecursively(any()) }
    }

    @Test
    fun `deleteMessage should return MessageNotFound error when message does not exist`() = runTest {
        val userId = 1L
        val messageId = 10L
        coEvery { messageDao.getMessageById(messageId) } returns MessageError.MessageNotFound(messageId).left()

        val result = messageService.deleteMessage(userId, messageId)

        assertTrue(result.isLeft())
        val err = (result as arrow.core.Either.Left).value as DeleteMessageError.MessageNotFound
        assertEquals(messageId, err.id)
        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 0) { sessionOwnershipDao.getOwner(any()) }
        coVerify(exactly = 0) { messageDao.deleteMessage(any()) }
    }

    @Test
    fun `deleteMessage should return AccessDenied error when user does not own session`() = runTest {
        val userId = 1L
        val otherUserId = 2L
        val messageId = 10L
        val sessionId = 5L
        val messageToDelete = ChatMessage.UserMessage(
            id = messageId,
            sessionId = sessionId,
            content = "Test message",
            createdAt = Instant.fromEpochMilliseconds(1234567890000L),
            updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )

        coEvery { messageDao.getMessageById(messageId) } returns messageToDelete.right()
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns otherUserId.right()

        val result = messageService.deleteMessage(userId, messageId)

        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is DeleteMessageError.AccessDenied)
        assertEquals(
            "User does not own the session containing this message",
            (error as DeleteMessageError.AccessDenied).reason
        )

        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 0) { messageDao.deleteMessage(any()) }
    }


    // === Leaf update cases specific to single delete ===
    @Test
    fun `deleteMessage should update to parent when deleting current leaf with no children`() = runTest {
        // Tree: P -> L(leaf). Delete L; new leaf should be P.
        val userId = 1L
        val sessionId = 10L
        val parentId = 1L
        val leafId = 2L

        val parent = testMessage1.copy(
            id = parentId,
            sessionId = sessionId,
            parentMessageId = null,
            childrenMessageIds = listOf(leafId)
        )
        val leaf = testMessage1.copy(
            id = leafId,
            sessionId = sessionId,
            parentMessageId = parentId,
            childrenMessageIds = emptyList()
        )
        val session = testSession.copy(id = sessionId, currentLeafMessageId = leafId)
        val allMessages = listOf(parent, leaf)

        coEvery { messageDao.getMessageById(leafId) } returns leaf.right()
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessage(leafId) } returns Unit.right()
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, parentId) } returns Unit.right()

        val result = messageService.deleteMessage(userId, leafId)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, parentId) }
    }

    @Test
    fun `deleteMessage should keep same leaf when deleting ancestor on current leaf path`() = runTest {
        // Tree: R -> A -> B(leaf). Delete A (non-leaf ancestor); children promoted, leaf remains B.
        val userId = 1L
        val sessionId = 10L
        val r = 1L
        val a = 2L
        val b = 3L

        val root =
            testMessage1.copy(id = r, sessionId = sessionId, parentMessageId = null, childrenMessageIds = listOf(a))
        val anc = testMessage1.copy(id = a, sessionId = sessionId, parentMessageId = r, childrenMessageIds = listOf(b))
        val leaf =
            testMessage1.copy(id = b, sessionId = sessionId, parentMessageId = a, childrenMessageIds = emptyList())

        val session = testSession.copy(id = sessionId, currentLeafMessageId = b)
        val allMessages = listOf(root, anc, leaf)

        coEvery { messageDao.getMessageById(a) } returns anc.right()
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessage(a) } returns Unit.right()
        // Expected: leaf remains b, so update is needed but value equals current; implementation sets needsUpdate=true and calls update.
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, b) } returns Unit.right()

        val result = messageService.deleteMessage(userId, a)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, b) }
    }

    @Test
    fun `deleteMessage should not update session when deleted message is outside current leaf path`() = runTest {
        // Two branches: R has children A (branch1->L1) and X (branch2->L2 current leaf). Delete A; leaf remains L2 and no update.
        val userId = 1L
        val sessionId = 10L
        val r = 1L
        val a = 2L
        val l1 = 3L
        val x = 4L
        val l2 = 5L

        val root =
            testMessage1.copy(id = r, sessionId = sessionId, parentMessageId = null, childrenMessageIds = listOf(a, x))
        val branchA =
            testMessage1.copy(id = a, sessionId = sessionId, parentMessageId = r, childrenMessageIds = listOf(l1))
        val leaf1 =
            testMessage1.copy(id = l1, sessionId = sessionId, parentMessageId = a, childrenMessageIds = emptyList())
        val branchX =
            testMessage1.copy(id = x, sessionId = sessionId, parentMessageId = r, childrenMessageIds = listOf(l2))
        val leaf2 =
            testMessage1.copy(id = l2, sessionId = sessionId, parentMessageId = x, childrenMessageIds = emptyList())

        val session = testSession.copy(id = sessionId, currentLeafMessageId = l2)
        val allMessages = listOf(root, branchA, leaf1, branchX, leaf2)

        coEvery { messageDao.getMessageById(a) } returns branchA.right()
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns allMessages
        coEvery { messageDao.deleteMessage(a) } returns Unit.right()

        val result = messageService.deleteMessage(userId, a)

        assertTrue(result.isRight())
        coVerify(exactly = 0) { sessionDao.updateSessionLeafMessageId(any(), any()) }
    }

    // === Error flows ===
    @Test
    fun `deleteMessage should return SessionUpdateFailed when session retrieval fails`() = runTest {
        val userId = 1L
        val messageId = 1L
        val sessionId = 10L
        val msg = testMessage1.copy(id = messageId, sessionId = sessionId)

        coEvery { messageDao.getMessageById(messageId) } returns msg.right()
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns SessionError.SessionNotFound(sessionId).left()

        val result = messageService.deleteMessage(userId, messageId)

        assertTrue(result.isLeft())
        val err = (result as arrow.core.Either.Left).value as DeleteMessageError.SessionUpdateFailed
        assertEquals(sessionId, err.sessionId)
        coVerify(exactly = 1) { messageDao.getMessageById(messageId) }
        coVerify(exactly = 1) { sessionOwnershipDao.getOwner(sessionId) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 0) { messageDao.deleteMessage(any()) }
    }

    @Test
    fun `deleteMessage should return SessionUpdateFailed when session leaf update fails`() = runTest {
        // Delete ancestor A on path to B(leaf). We expect to call update with B; simulate failure.
        val userId = 1L
        val sessionId = 10L
        val r = 1L
        val a = 2L
        val b = 3L
        val root =
            testMessage1.copy(id = r, sessionId = sessionId, parentMessageId = null, childrenMessageIds = listOf(a))
        val anc = testMessage1.copy(id = a, sessionId = sessionId, parentMessageId = r, childrenMessageIds = listOf(b))
        val leaf =
            testMessage1.copy(id = b, sessionId = sessionId, parentMessageId = a, childrenMessageIds = emptyList())
        val session = testSession.copy(id = sessionId, currentLeafMessageId = b)
        val all = listOf(root, anc, leaf)

        coEvery { messageDao.getMessageById(a) } returns anc.right()
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.getMessagesBySessionId(sessionId) } returns all
        coEvery { messageDao.deleteMessage(a) } returns Unit.right()
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, b) } returns SessionError.SessionNotFound(sessionId)
            .left()

        val result = messageService.deleteMessage(userId, a)

        assertTrue(result.isLeft())
        val err = (result as arrow.core.Either.Left).value as DeleteMessageError.SessionUpdateFailed
        assertEquals(sessionId, err.sessionId)
    }

    @Test
    fun `deleteMessage should handle deletion failure after successful session checks`() = runTest {
        val userId = 1L
        val messageId = 1L
        val sessionId = 10L
        val msg = testMessage1.copy(id = messageId, sessionId = sessionId)
        val session = testSession.copy(id = sessionId, currentLeafMessageId = null)

        coEvery { messageDao.getMessageById(messageId) } returns msg.right()
        coEvery { sessionOwnershipDao.getOwner(sessionId) } returns userId.right()
        coEvery { sessionDao.getSessionById(sessionId) } returns session.right()
        coEvery { messageDao.deleteMessage(messageId) } returns MessageError.MessageNotFound(messageId).left()

        val result = messageService.deleteMessage(userId, messageId)

        assertTrue(result.isLeft())
        val err = (result as arrow.core.Either.Left).value as DeleteMessageError.MessageNotFound
        assertEquals(messageId, err.id)
    }
}
