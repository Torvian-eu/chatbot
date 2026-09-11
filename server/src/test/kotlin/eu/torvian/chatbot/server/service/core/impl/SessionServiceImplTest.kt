package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
 * Unit tests for [SessionServiceImpl].
 *
 * This test suite verifies that [SessionServiceImpl] correctly orchestrates
 * calls to the underlying DAO and handles business logic validation.
 * All dependencies ([SessionDao], [SessionOwnershipDao], [MessageDao], [ToolCallDao],
 * [AgentRoleDao], [TransactionScope]) are mocked using MockK.
 */
class SessionServiceImplTest {

    // Mocked dependencies
    private lateinit var sessionDao: SessionDao
    private lateinit var sessionOwnershipDao: SessionOwnershipDao
    private lateinit var messageDao: MessageDao
    private lateinit var toolCallDao: ToolCallDao
    private lateinit var agentRoleDao: AgentRoleDao
    private lateinit var transactionScope: TransactionScope

    // Class under test
    private lateinit var sessionService: SessionServiceImpl

    // Test data
    private val testSessionSummary1 = ChatSessionSummary(
        id = 1L,
        name = "Test Session 1",
        groupId = 1L,
        groupName = "Test Group",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L)
    )

    private val testSessionSummary2 = ChatSessionSummary(
        id = 2L,
        name = "Test Session 2",
        groupId = null,
        groupName = null,
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L)
    )

    private val testSession = ChatSession(
        id = 1L,
        name = "Test Session",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        groupId = 1L,
        agentRoleId = null,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        sessionDao = mockk()
        sessionOwnershipDao = mockk()
        messageDao = mockk()
        toolCallDao = mockk()
        agentRoleDao = mockk()
        transactionScope = mockk()

        // Create the service instance with mocked dependencies
        sessionService = SessionServiceImpl(
            sessionDao,
            sessionOwnershipDao,
            messageDao,
            toolCallDao,
            agentRoleDao,
            transactionScope
        )

        // Default the legality-related reads so existing tests observe a legal pair by default: a session
        // without a project and a role without memberships, plus successful read/write paths for the
        // project-selection flow (specific stubs in individual tests win over these wildcards).
        coEvery { sessionDao.getSessionProjectSelection(any()) } returns
            SessionProjectSelection(projectId = null, agentRoleId = null).right()
        coEvery { sessionDao.updateSessionProjectId(any(), any()) } returns Unit.right()
        coEvery { sessionDao.updateSessionAgentRoleId(any(), any()) } returns Unit.right()

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
            sessionDao,
            sessionOwnershipDao,
            messageDao,
            toolCallDao,
            agentRoleDao,
            transactionScope
        )
    }

    // --- getAllSessionsSummaries Tests ---

    @Test
    fun `getAllSessionsSummaries should return list of session summaries from DAO`() = runTest {
        // Arrange
        val userId = 1L
        val expectedSummaries = listOf(testSessionSummary1, testSessionSummary2)
        coEvery { sessionOwnershipDao.getAllSessionsForUser(userId) } returns expectedSummaries

        // Act
        val result = sessionService.getAllSessionsSummaries(userId)

        // Assert
        assertEquals(expectedSummaries, result, "Should return the session summaries from DAO")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionOwnershipDao.getAllSessionsForUser(userId) }
    }

    @Test
    fun `getAllSessionsSummaries should return empty list when no sessions exist`() = runTest {
        // Arrange
        val userId = 1L
        coEvery { sessionOwnershipDao.getAllSessionsForUser(userId) } returns emptyList()

        // Act
        val result = sessionService.getAllSessionsSummaries(userId)

        // Assert
        assertTrue(result.isEmpty(), "Should return empty list when no sessions exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionOwnershipDao.getAllSessionsForUser(userId) }
    }

    // --- createSession Tests ---

    @Test
    fun `createSession should create session successfully with valid name`() = runTest {
        // Arrange
        val userId = 1L
        val sessionName = "New Session"
        coEvery { sessionDao.insertSession(sessionName) } returns testSession.right()
        coEvery { sessionOwnershipDao.setOwner(testSession.id, userId) } returns Unit.right()

        // Act
        val result = sessionService.createSession(userId, sessionName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testSession, result.getOrNull(), "Should return the created session")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.insertSession(sessionName) }
        coVerify(exactly = 1) { sessionOwnershipDao.setOwner(testSession.id, userId) }
    }


    @Test
    fun `createSession should normalize line breaks in session name`() = runTest {
        // Arrange
        val userId = 1L
        val rawName = "Hello\nWorld\r\nSession"
        val normalizedName = "Hello World Session"
        coEvery { sessionDao.insertSession(normalizedName) } returns testSession.right()
        coEvery { sessionOwnershipDao.setOwner(testSession.id, userId) } returns Unit.right()

        // Act
        val result = sessionService.createSession(userId, rawName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        coVerify(exactly = 1) { sessionDao.insertSession(normalizedName) }
    }

    @Test
    fun `createSession should return NameTooLong error when name exceeds max length`() = runTest {
        // Arrange
        val userId = 1L
        val tooLongName = "x".repeat(256)

        // Act
        val result = sessionService.createSession(userId, tooLongName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for too-long name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<CreateSessionError.NameTooLong>(error, "Should be NameTooLong error")
        assertEquals(255, error.maxLength)
        coVerify(exactly = 0) { sessionDao.insertSession(any()) }
    }

    @Test
    fun `createSession should accept name at exactly max length`() = runTest {
        // Arrange
        val userId = 1L
        val maxLengthName = "x".repeat(255)
        coEvery { sessionDao.insertSession(maxLengthName) } returns testSession.right()
        coEvery { sessionOwnershipDao.setOwner(testSession.id, userId) } returns Unit.right()

        // Act
        val result = sessionService.createSession(userId, maxLengthName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for name at exactly max length")
        coVerify(exactly = 1) { sessionDao.insertSession(maxLengthName) }
    }

    @Test
    fun `createSession should return InvalidName error for blank name`() = runTest {
        // Arrange
        val userId = 1L
        val blankName = "   "

        // Act
        val result = sessionService.createSession(userId, blankName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<CreateSessionError.InvalidName>(error, "Should be InvalidName error")
        assertEquals("Session name cannot be blank.", error.reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { sessionDao.insertSession(any()) }
    }

    @Test
    fun `createSession should return InvalidRelatedEntity error for foreign key violation`() = runTest {
        // Arrange
        val userId = 1L
        val sessionName = "New Session"
        val daoError = SessionError.ForeignKeyViolation("Invalid group ID")
        coEvery { sessionDao.insertSession(sessionName) } returns daoError.left()

        // Act
        val result = sessionService.createSession(userId, sessionName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for foreign key violation")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<CreateSessionError.InvalidRelatedEntity>(error, "Should be InvalidRelatedEntity error")
        assertEquals("Invalid group ID", error.message)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.insertSession(sessionName) }
    }

    // --- getSessionDetails Tests ---

    @Test
    fun `getSessionDetails should return session when it exists`() = runTest {
        // Arrange
        val sessionId = 1L
        coEvery { sessionDao.getSessionById(sessionId) } returns testSession.right()

        // Act
        val result = sessionService.getSessionDetails(sessionId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for existing session")
        assertEquals(testSession, result.getOrNull(), "Should return the correct session")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
    }

    @Test
    fun `getSessionDetails should return SessionNotFound error when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        coEvery { sessionDao.getSessionById(sessionId) } returns SessionError.SessionNotFound(sessionId).left()

        // Act
        val result = sessionService.getSessionDetails(sessionId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<GetSessionDetailsError.SessionNotFound>(error, "Should be SessionNotFound error")
        assertEquals(sessionId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
    }

    // --- updateSessionName Tests ---

    @Test
    fun `updateSessionName should update session name successfully`() = runTest {
        // Arrange
        val sessionId = 1L
        val newName = "Updated Session Name"
        coEvery { sessionDao.updateSessionName(sessionId, newName) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionName(sessionId, newName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionName(sessionId, newName) }
    }

    @Test
    fun `updateSessionName should normalize line breaks in session name`() = runTest {
        // Arrange
        val sessionId = 1L
        val rawName = "Updated\nSession\rName"
        val normalizedName = "Updated Session Name"
        coEvery { sessionDao.updateSessionName(sessionId, normalizedName) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionName(sessionId, rawName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { sessionDao.updateSessionName(sessionId, normalizedName) }
    }

    @Test
    fun `updateSessionName should return NameTooLong error when name exceeds max length`() = runTest {
        // Arrange
        val sessionId = 1L
        val tooLongName = "x".repeat(256)

        // Act
        val result = sessionService.updateSessionName(sessionId, tooLongName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for too-long name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateSessionNameError.NameTooLong>(error, "Should be NameTooLong error")
        assertEquals(255, error.maxLength)
        coVerify(exactly = 0) { sessionDao.updateSessionName(any(), any()) }
    }

    @Test
    fun `updateSessionName should accept name at exactly max length`() = runTest {
        // Arrange
        val sessionId = 1L
        val maxLengthName = "x".repeat(255)
        coEvery { sessionDao.updateSessionName(sessionId, maxLengthName) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionName(sessionId, maxLengthName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for name at exactly max length")
        coVerify(exactly = 1) { sessionDao.updateSessionName(sessionId, maxLengthName) }
    }

    @Test
    fun `updateSessionName should return InvalidName error for blank name`() = runTest {
        // Arrange
        val sessionId = 1L
        val blankName = "  "

        // Act
        val result = sessionService.updateSessionName(sessionId, blankName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateSessionNameError.InvalidName>(error, "Should be InvalidName error")
        assertEquals("Session name cannot be blank.", error.reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { sessionDao.updateSessionName(any(), any()) }
    }

    @Test
    fun `updateSessionName should return SessionNotFound error when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        val newName = "Updated Name"
        coEvery { sessionDao.updateSessionName(sessionId, newName) } returns SessionError.SessionNotFound(sessionId)
            .left()

        // Act
        val result = sessionService.updateSessionName(sessionId, newName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateSessionNameError.SessionNotFound>(error, "Should be SessionNotFound error")
        assertEquals(sessionId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionName(sessionId, newName) }
    }

    // --- updateSessionGroupId Tests ---

    @Test
    fun `updateSessionGroupId should update session group ID successfully`() = runTest {
        // Arrange
        val sessionId = 1L
        val groupId = 2L
        coEvery { sessionDao.updateSessionGroupId(sessionId, groupId) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionGroupId(sessionId, groupId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionGroupId(sessionId, groupId) }
    }

    @Test
    fun `updateSessionGroupId should update session group ID to null successfully`() = runTest {
        // Arrange
        val sessionId = 1L
        coEvery { sessionDao.updateSessionGroupId(sessionId, null) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionGroupId(sessionId, null)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update to null")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionGroupId(sessionId, null) }
    }

    @Test
    fun `updateSessionGroupId should return SessionNotFound error when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        val groupId = 1L
        coEvery { sessionDao.updateSessionGroupId(sessionId, groupId) } returns SessionError.SessionNotFound(sessionId)
            .left()

        // Act
        val result = sessionService.updateSessionGroupId(sessionId, groupId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateSessionGroupIdError.SessionNotFound>(error, "Should be SessionNotFound error")
        assertEquals(sessionId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionGroupId(sessionId, groupId) }
    }

    // --- updateSessionAgentRoleId Tests ---

    @Test
    fun `updateSessionAgentRoleId should update session agent role ID successfully when role exists`() = runTest {
        // Arrange
        val sessionId = 1L
        val agentRoleId = 2L
        val agentRole = TestDefaults.agentRole1

        coEvery { agentRoleDao.getRoleById(agentRoleId) } returns agentRole.right()
        coEvery { sessionDao.updateSessionAgentRoleId(sessionId, agentRoleId) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionAgentRoleId(sessionId, agentRoleId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update with existing role")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { agentRoleDao.getRoleById(agentRoleId) }
        coVerify(exactly = 1) { sessionDao.updateSessionAgentRoleId(sessionId, agentRoleId) }
    }

    @Test
    fun `updateSessionAgentRoleId should update session agent role ID successfully when agentRoleId is null`() =
        runTest {
            // Arrange
            val sessionId = 1L
            val agentRoleId: Long? = null

            coEvery { sessionDao.updateSessionAgentRoleId(sessionId, null) } returns Unit.right()

            // Act
            val result = sessionService.updateSessionAgentRoleId(sessionId, agentRoleId)

            // Assert
            assertTrue(result.isRight(), "Should return Right for successful deselect")
            coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
            coVerify(exactly = 0) { agentRoleDao.getRoleById(any()) }
            coVerify(exactly = 1) { sessionDao.updateSessionAgentRoleId(sessionId, null) }
        }

    @Test
    fun `updateSessionAgentRoleId should return AgentRoleNotFound error when role does not exist`() = runTest {
        // Arrange
        val sessionId = 1L
        val agentRoleId = 999L
        coEvery { agentRoleDao.getRoleById(agentRoleId) } returns eu.torvian.chatbot.server.data.dao.error.AgentRoleError.NotFound(
            agentRoleId
        ).left()

        // Act
        val result = sessionService.updateSessionAgentRoleId(sessionId, agentRoleId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent role")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateSessionAgentRoleIdError.AgentRoleNotFound>(error, "Should be AgentRoleNotFound error")
        assertEquals(agentRoleId, error.agentRoleId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { agentRoleDao.getRoleById(agentRoleId) }
        coVerify(exactly = 0) { sessionDao.updateSessionAgentRoleId(any(), any()) }
    }

    @Test
    fun `updateSessionAgentRoleId should return SessionNotFound error when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        val agentRoleId = 1L
        coEvery { agentRoleDao.getRoleById(agentRoleId) } returns TestDefaults.agentRole1.right()
        coEvery { sessionDao.updateSessionAgentRoleId(sessionId, agentRoleId) } returns SessionError.SessionNotFound(
            sessionId
        ).left()

        // Act
        val result = sessionService.updateSessionAgentRoleId(sessionId, agentRoleId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateSessionAgentRoleIdError.SessionNotFound>(error, "Should be SessionNotFound error")
        assertEquals(sessionId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { agentRoleDao.getRoleById(agentRoleId) }
        coVerify(exactly = 1) { sessionDao.updateSessionAgentRoleId(sessionId, agentRoleId) }
    }

    // --- updateSessionLeafMessageId Tests ---

    @Test
    fun `updateSessionLeafMessageId should update session leaf message ID successfully`() = runTest {
        // Arrange
        val sessionId = 1L
        val messageId = 2L
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, messageId) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionLeafMessageId(sessionId, messageId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, messageId) }
    }

    @Test
    fun `updateSessionLeafMessageId should return SessionNotFound error when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        val messageId = 1L
        GetOwnerError.ResourceNotFound(sessionId.toString())
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, messageId) } returns SessionError.SessionNotFound(
            sessionId
        ).left()

        // Act
        val result = sessionService.updateSessionLeafMessageId(sessionId, messageId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateSessionLeafMessageIdError.SessionNotFound>(error, "Should be SessionNotFound error")
        assertEquals(sessionId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, messageId) }
    }

    @Test
    fun `updateSessionLeafMessageId should return InvalidRelatedEntity error for foreign key violation`() = runTest {
        // Arrange
        val sessionId = 1L
        val messageId = 999L
        val daoError = SessionError.ForeignKeyViolation("Invalid message ID")
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, messageId) } returns daoError.left()

        // Act
        val result = sessionService.updateSessionLeafMessageId(sessionId, messageId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for foreign key violation")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateSessionLeafMessageIdError.InvalidRelatedEntity>(error, "Should be InvalidRelatedEntity error")
        assertEquals("Invalid message ID", error.message)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionLeafMessageId(sessionId, messageId) }
    }

    // --- deleteSession Tests ---

    @Test
    fun `deleteSession should delete session successfully`() = runTest {
        // Arrange
        val sessionId = 1L
        coEvery { sessionDao.deleteSession(sessionId) } returns Unit.right()

        // Act
        val result = sessionService.deleteSession(sessionId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.deleteSession(sessionId) }
    }

    @Test
    fun `deleteSession should return SessionNotFound error when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        coEvery { sessionDao.deleteSession(sessionId) } returns SessionError.SessionNotFound(sessionId).left()

        // Act
        val result = sessionService.deleteSession(sessionId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<DeleteSessionError.SessionNotFound>(error, "Should be SessionNotFound error")
        assertEquals(sessionId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.deleteSession(sessionId) }
    }

    // --- Session Legality Invariant on role attach ---

    @Test
    fun `updateSessionAgentRoleId rejects a role not in the session's project`() = runTest {
        // Session selects project 10; the role belongs only to project 20, so the attach would leave
        // an illegal pair.
        val sessionId = 1L
        val roleId = 2L
        val projectId = 10L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(projectId = 20L).right()
        coEvery { sessionDao.getSessionProjectSelection(sessionId) } returns
            SessionProjectSelection(projectId = projectId, agentRoleId = null).right()

        val result = sessionService.updateSessionAgentRoleId(sessionId, roleId)

        val error = assertIs<UpdateSessionAgentRoleIdError.AgentRoleNotInProject>(result.leftOrNull())
        assertEquals(roleId, error.agentRoleId)
        assertEquals(projectId, error.projectId)
        // The legality check runs BEFORE the write: no role may be attached.
        coVerify(exactly = 0) { sessionDao.updateSessionAgentRoleId(any(), any()) }
    }

    @Test
    fun `updateSessionAgentRoleId rejects a project-associated role on a project-less session`() = runTest {
        val sessionId = 1L
        val roleId = 2L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(projectId = 10L).right()
        // Session has no project; the role belongs to one — attaching it would be illegal.
        coEvery { sessionDao.getSessionProjectSelection(sessionId) } returns
            SessionProjectSelection(projectId = null, agentRoleId = null).right()

        val result = sessionService.updateSessionAgentRoleId(sessionId, roleId)

        val error = assertIs<UpdateSessionAgentRoleIdError.AgentRoleNotInProject>(result.leftOrNull())
        assertEquals(roleId, error.agentRoleId)
        assertEquals(null, error.projectId)
        coVerify(exactly = 0) { sessionDao.updateSessionAgentRoleId(any(), any()) }
    }

    @Test
    fun `updateSessionAgentRoleId allows a role that belongs to the session's project`() = runTest {
        val sessionId = 1L
        val roleId = 2L
        val projectId = 10L
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(projectId = projectId).right()
        coEvery { sessionDao.getSessionProjectSelection(sessionId) } returns
            SessionProjectSelection(projectId = projectId, agentRoleId = null).right()
        coEvery { sessionDao.updateSessionAgentRoleId(sessionId, roleId) } returns Unit.right()

        val result = sessionService.updateSessionAgentRoleId(sessionId, roleId)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { sessionDao.updateSessionAgentRoleId(sessionId, roleId) }
    }

    // --- project selection clears illegal roles atomically ---

    @Test
    fun `updateSessionProjectId clears the role when the new project excludes it`() = runTest {
        val sessionId = 1L
        val roleId = 2L
        val projectId = 10L
        coEvery { sessionDao.getSessionProjectSelection(sessionId) } returns
            SessionProjectSelection(projectId = null, agentRoleId = roleId).right()
        // The role belongs to project 20 only; switching to 10 makes the pair illegal.
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(projectId = 20L).right()
        coEvery { sessionDao.updateSessionProjectId(sessionId, projectId) } returns Unit.right()
        coEvery { sessionDao.updateSessionAgentRoleId(sessionId, null) } returns Unit.right()

        val result = sessionService.updateSessionProjectId(sessionId, projectId)

        assertTrue(result.isRight())
        val selection = result.getOrNull()
        assertNotNull(selection)
        assertEquals(projectId, selection.projectId)
        assertEquals(null, selection.agentRoleId, "the illegal role must be cleared")
        // Project write first, then the role clear, inside the same transaction.
        coVerifyOrder {
            sessionDao.updateSessionProjectId(sessionId, projectId)
            sessionDao.updateSessionAgentRoleId(sessionId, null)
        }
    }

    @Test
    fun `updateSessionProjectId clears the role when deselecting while it has projects`() = runTest {
        val sessionId = 1L
        val roleId = 2L
        coEvery { sessionDao.getSessionProjectSelection(sessionId) } returns
            SessionProjectSelection(projectId = 10L, agentRoleId = roleId).right()
        // The role belongs to project 10; deselecting to null leaves it illegal (role has a project).
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(projectId = 10L).right()
        coEvery { sessionDao.updateSessionProjectId(sessionId, null) } returns Unit.right()
        coEvery { sessionDao.updateSessionAgentRoleId(sessionId, null) } returns Unit.right()

        val result = sessionService.updateSessionProjectId(sessionId, null)

        assertTrue(result.isRight())
        assertEquals(null, result.getOrNull()?.projectId)
        assertEquals(null, result.getOrNull()?.agentRoleId)
        coVerify(exactly = 1) { sessionDao.updateSessionAgentRoleId(sessionId, null) }
    }

    @Test
    fun `updateSessionProjectId keeps a role that belongs to the new project`() = runTest {
        val sessionId = 1L
        val roleId = 2L
        val projectId = 10L
        coEvery { sessionDao.getSessionProjectSelection(sessionId) } returns
            SessionProjectSelection(projectId = null, agentRoleId = roleId).right()
        coEvery { agentRoleDao.getRoleById(roleId) } returns TestDefaults.agentRole1.copy(projectId = projectId).right()
        coEvery { sessionDao.updateSessionProjectId(sessionId, projectId) } returns Unit.right()

        val result = sessionService.updateSessionProjectId(sessionId, projectId)

        assertTrue(result.isRight())
        assertEquals(projectId, result.getOrNull()?.projectId)
        assertEquals(roleId, result.getOrNull()?.agentRoleId, "a legal role stays attached")
        // No clear ran: the role remains legal under the new selection.
        coVerify(exactly = 0) { sessionDao.updateSessionAgentRoleId(sessionId, null) }
    }

    @Test
    fun `updateSessionProjectId assigns a project without touching a role-less session`() = runTest {
        val sessionId = 1L
        val projectId = 10L
        coEvery { sessionDao.getSessionProjectSelection(sessionId) } returns
            SessionProjectSelection(projectId = null, agentRoleId = null).right()
        coEvery { sessionDao.updateSessionProjectId(sessionId, projectId) } returns Unit.right()

        val result = sessionService.updateSessionProjectId(sessionId, projectId)

        assertTrue(result.isRight())
        assertEquals(projectId, result.getOrNull()?.projectId)
        assertEquals(null, result.getOrNull()?.agentRoleId)
        coVerify(exactly = 0) { sessionDao.updateSessionAgentRoleId(any(), any()) }
    }

    @Test
    fun `updateSessionProjectId returns SessionNotFound when the session is missing`() = runTest {
        val sessionId = 999L
        coEvery { sessionDao.getSessionProjectSelection(sessionId) } returns
            SessionError.SessionNotFound(sessionId).left()

        val result = sessionService.updateSessionProjectId(sessionId, 10L)

        val error = assertIs<UpdateSessionProjectIdError.SessionNotFound>(result.leftOrNull())
        assertEquals(sessionId, error.id)
        coVerify(exactly = 0) { sessionDao.updateSessionProjectId(any(), any()) }
    }

    @Test
    fun `updateSessionProjectId maps a foreign key violation to ProjectNotFound`() = runTest {
        val sessionId = 1L
        coEvery { sessionDao.getSessionProjectSelection(sessionId) } returns
            SessionProjectSelection(projectId = null, agentRoleId = null).right()
        // The project was deleted concurrently between the route's ownership check and this write.
        coEvery { sessionDao.updateSessionProjectId(sessionId, 10L) } returns
            SessionError.ForeignKeyViolation("no such project 10").left()

        val result = sessionService.updateSessionProjectId(sessionId, 10L)

        val error = assertIs<UpdateSessionProjectIdError.ProjectNotFound>(result.leftOrNull())
        assertEquals(10L, error.projectId)
    }
}
