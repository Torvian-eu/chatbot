package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.service.core.error.session.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Unit tests for [SessionServiceImpl].
 *
 * This test suite verifies that [SessionServiceImpl] correctly orchestrates
 * calls to the underlying DAO and handles business logic validation.
 * All dependencies ([SessionDao], [SettingsDao], [ModelDao], [TransactionScope]) are mocked using MockK.
 */
class SessionServiceImplTest {

    // Mocked dependencies
    private lateinit var sessionDao: SessionDao
    private lateinit var settingsDao: SettingsDao
    private lateinit var modelDao: ModelDao
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
        currentModelId = 1L,
        currentSettingsId = 1L,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        sessionDao = mockk()
        settingsDao = mockk()
        modelDao = mockk()
        transactionScope = mockk()

        // Create the service instance with mocked dependencies
        sessionService = SessionServiceImpl(sessionDao, settingsDao, modelDao, transactionScope)

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(sessionDao, settingsDao, modelDao, transactionScope)
    }

    // --- getAllSessionsSummaries Tests ---

    @Test
    fun `getAllSessionsSummaries should return list of session summaries from DAO`() = runTest {
        // Arrange
        val expectedSummaries = listOf(testSessionSummary1, testSessionSummary2)
        coEvery { sessionDao.getAllSessions() } returns expectedSummaries

        // Act
        val result = sessionService.getAllSessionsSummaries()

        // Assert
        assertEquals(expectedSummaries, result, "Should return the session summaries from DAO")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getAllSessions() }
    }

    @Test
    fun `getAllSessionsSummaries should return empty list when no sessions exist`() = runTest {
        // Arrange
        coEvery { sessionDao.getAllSessions() } returns emptyList()

        // Act
        val result = sessionService.getAllSessionsSummaries()

        // Assert
        assertTrue(result.isEmpty(), "Should return empty list when no sessions exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getAllSessions() }
    }

    // --- createSession Tests ---

    @Test
    fun `createSession should create session successfully with valid name`() = runTest {
        // Arrange
        val sessionName = "New Session"
        coEvery { sessionDao.insertSession(sessionName) } returns testSession.right()

        // Act
        val result = sessionService.createSession(sessionName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testSession, result.getOrNull(), "Should return the created session")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.insertSession(sessionName) }
    }

    @Test
    fun `createSession should create session successfully with null name using default`() = runTest {
        // Arrange
        val defaultName = "New Chat"
        coEvery { sessionDao.insertSession(defaultName) } returns testSession.right()

        // Act
        val result = sessionService.createSession(null)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testSession, result.getOrNull(), "Should return the created session")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.insertSession(defaultName) }
    }

    @Test
    fun `createSession should return InvalidName error for blank name`() = runTest {
        // Arrange
        val blankName = "   "

        // Act
        val result = sessionService.createSession(blankName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is CreateSessionError.InvalidName, "Should be InvalidName error")
        assertEquals("Session name cannot be blank.", (error as CreateSessionError.InvalidName).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { sessionDao.insertSession(any()) }
    }

    @Test
    fun `createSession should return InvalidRelatedEntity error for foreign key violation`() = runTest {
        // Arrange
        val sessionName = "New Session"
        val daoError = SessionError.ForeignKeyViolation("Invalid group ID")
        coEvery { sessionDao.insertSession(sessionName) } returns daoError.left()

        // Act
        val result = sessionService.createSession(sessionName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for foreign key violation")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is CreateSessionError.InvalidRelatedEntity, "Should be InvalidRelatedEntity error")
        assertEquals("Invalid group ID", (error as CreateSessionError.InvalidRelatedEntity).message)
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
        val daoError = SessionError.SessionNotFound(sessionId)
        coEvery { sessionDao.getSessionById(sessionId) } returns daoError.left()

        // Act
        val result = sessionService.getSessionDetails(sessionId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is GetSessionDetailsError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as GetSessionDetailsError.SessionNotFound).id)
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
        assertTrue(error is UpdateSessionNameError.InvalidName, "Should be InvalidName error")
        assertEquals("Session name cannot be blank.", (error as UpdateSessionNameError.InvalidName).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { sessionDao.updateSessionName(any(), any()) }
    }

    @Test
    fun `updateSessionName should return SessionNotFound error when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        val newName = "Updated Name"
        val daoError = SessionError.SessionNotFound(sessionId)
        coEvery { sessionDao.updateSessionName(sessionId, newName) } returns daoError.left()

        // Act
        val result = sessionService.updateSessionName(sessionId, newName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSessionNameError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as UpdateSessionNameError.SessionNotFound).id)
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
        val daoError = SessionError.SessionNotFound(sessionId)
        coEvery { sessionDao.updateSessionGroupId(sessionId, groupId) } returns daoError.left()

        // Act
        val result = sessionService.updateSessionGroupId(sessionId, groupId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSessionGroupIdError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as UpdateSessionGroupIdError.SessionNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionGroupId(sessionId, groupId) }
    }

    @Test
    fun `updateSessionGroupId should return InvalidRelatedEntity error for foreign key violation`() = runTest {
        // Arrange
        val sessionId = 1L
        val groupId = 999L
        val daoError = SessionError.ForeignKeyViolation("Invalid group ID")
        coEvery { sessionDao.updateSessionGroupId(sessionId, groupId) } returns daoError.left()

        // Act
        val result = sessionService.updateSessionGroupId(sessionId, groupId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for foreign key violation")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSessionGroupIdError.InvalidRelatedEntity, "Should be InvalidRelatedEntity error")
        assertEquals("Invalid group ID", (error as UpdateSessionGroupIdError.InvalidRelatedEntity).message)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionGroupId(sessionId, groupId) }
    }

    // --- updateSessionCurrentModelId Tests ---

    @Test
    fun `updateSessionCurrentModelId should update session model ID successfully`() = runTest {
        // Arrange
        val sessionId = 1L
        val modelId = 2L
        coEvery { sessionDao.updateSessionCurrentModelId(sessionId, modelId) } returns Unit.right()
        coEvery { sessionDao.updateSessionCurrentSettingsId(sessionId, null) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionCurrentModelId(sessionId, modelId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionCurrentModelId(sessionId, modelId) }
        coVerify(exactly = 1) { sessionDao.updateSessionCurrentSettingsId(sessionId, null) }
    }

    @Test
    fun `updateSessionCurrentModelId should return SessionNotFound error when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        val modelId = 1L
        val daoError = SessionError.SessionNotFound(sessionId)
        coEvery { sessionDao.updateSessionCurrentModelId(sessionId, modelId) } returns daoError.left()

        // Act
        val result = sessionService.updateSessionCurrentModelId(sessionId, modelId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSessionCurrentModelIdError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as UpdateSessionCurrentModelIdError.SessionNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionCurrentModelId(sessionId, modelId) }
    }

    @Test
    fun `updateSessionCurrentModelId should return InvalidRelatedEntity error for foreign key violation`() = runTest {
        // Arrange
        val sessionId = 1L
        val modelId = 999L
        val daoError = SessionError.ForeignKeyViolation("Invalid model ID")
        coEvery { sessionDao.updateSessionCurrentModelId(sessionId, modelId) } returns daoError.left()

        // Act
        val result = sessionService.updateSessionCurrentModelId(sessionId, modelId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for foreign key violation")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSessionCurrentModelIdError.InvalidRelatedEntity, "Should be InvalidRelatedEntity error")
        assertEquals("Invalid model ID", (error as UpdateSessionCurrentModelIdError.InvalidRelatedEntity).message)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.updateSessionCurrentModelId(sessionId, modelId) }
    }

    // --- updateSessionCurrentSettingsId Tests ---

    @Test
    fun `updateSessionCurrentSettingsId should update session settings ID successfully`() = runTest {
        // Arrange
        val sessionId = 1L
        val settingsId = 2L
        val modelId = 1L

        // Create test session with current model
        val sessionWithModel = testSession.copy(currentModelId = modelId)

        // Create test settings that belong to the same model
        val testSettings = eu.torvian.chatbot.common.models.ChatModelSettings(
            id = settingsId,
            modelId = modelId,
            name = "Test Settings",
            systemMessage = "Test system message"
        )

        // Mock the validation calls
        coEvery { sessionDao.getSessionById(sessionId) } returns sessionWithModel.right()
        coEvery { settingsDao.getSettingsById(settingsId) } returns testSettings.right()
        coEvery { sessionDao.updateSessionCurrentSettingsId(sessionId, settingsId) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionCurrentSettingsId(sessionId, settingsId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { settingsDao.getSettingsById(settingsId) }
        coVerify(exactly = 1) { sessionDao.updateSessionCurrentSettingsId(sessionId, settingsId) }
    }

    @Test
    fun `updateSessionCurrentSettingsId should update to null settings ID successfully`() = runTest {
        // Arrange
        val sessionId = 1L
        val settingsId: Long? = null

        // When settingsId is null, no validation calls should be made
        coEvery { sessionDao.updateSessionCurrentSettingsId(sessionId, null) } returns Unit.right()

        // Act
        val result = sessionService.updateSessionCurrentSettingsId(sessionId, settingsId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update to null")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { sessionDao.getSessionById(any()) }
        coVerify(exactly = 0) { settingsDao.getSettingsById(any()) }
        coVerify(exactly = 1) { sessionDao.updateSessionCurrentSettingsId(sessionId, null) }
    }

    @Test
    fun `updateSessionCurrentSettingsId should return SessionNotFound error when session does not exist`() = runTest {
        // Arrange
        val sessionId = 999L
        val settingsId = 1L
        val daoError = SessionError.SessionNotFound(sessionId)

        // Mock the session lookup (which will fail first)
        coEvery { sessionDao.getSessionById(sessionId) } returns daoError.left()

        // Act
        val result = sessionService.updateSessionCurrentSettingsId(sessionId, settingsId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSessionCurrentSettingsIdError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as UpdateSessionCurrentSettingsIdError.SessionNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        // Should not get to settings validation or update
        coVerify(exactly = 0) { settingsDao.getSettingsById(any()) }
        coVerify(exactly = 0) { sessionDao.updateSessionCurrentSettingsId(any(), any()) }
    }

    @Test
    fun `updateSessionCurrentSettingsId should return InvalidRelatedEntity error for foreign key violation`() = runTest {
        // Arrange
        val sessionId = 1L
        val settingsId = 999L
        val modelId = 1L

        // Create test session with current model
        val sessionWithModel = testSession.copy(currentModelId = modelId)

        // Mock session lookup success but settings lookup failure
        coEvery { sessionDao.getSessionById(sessionId) } returns sessionWithModel.right()
        coEvery { settingsDao.getSettingsById(settingsId) } returns eu.torvian.chatbot.server.data.dao.error.SettingsError.SettingsNotFound(settingsId).left()

        // Act
        val result = sessionService.updateSessionCurrentSettingsId(sessionId, settingsId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent settings")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity, "Should be InvalidRelatedEntity error")
        assertEquals("Settings with ID $settingsId not found", (error as UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity).message)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { settingsDao.getSettingsById(settingsId) }
        // Should not get to the final update
        coVerify(exactly = 0) { sessionDao.updateSessionCurrentSettingsId(any(), any()) }
    }

    @Test
    fun `updateSessionCurrentSettingsId should return SettingsModelMismatch error when settings belong to different model`() = runTest {
        // Arrange
        val sessionId = 1L
        val settingsId = 2L
        val sessionModelId = 1L
        val settingsModelId = 3L // Different model

        // Create test session with one model
        val sessionWithModel = testSession.copy(currentModelId = sessionModelId)

        // Create test settings that belong to a different model
        val testSettings = eu.torvian.chatbot.common.models.ChatModelSettings(
            id = settingsId,
            modelId = settingsModelId, // Different from session model
            name = "Test Settings",
            systemMessage = "Test system message"
        )

        // Mock the validation calls
        coEvery { sessionDao.getSessionById(sessionId) } returns sessionWithModel.right()
        coEvery { settingsDao.getSettingsById(settingsId) } returns testSettings.right()

        // Act
        val result = sessionService.updateSessionCurrentSettingsId(sessionId, settingsId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for model mismatch")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSessionCurrentSettingsIdError.SettingsModelMismatch, "Should be SettingsModelMismatch error")
        val mismatchError = error as UpdateSessionCurrentSettingsIdError.SettingsModelMismatch
        assertEquals(settingsId, mismatchError.settingsId)
        assertEquals(settingsModelId, mismatchError.settingsModelId)
        assertEquals(sessionModelId, mismatchError.sessionModelId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.getSessionById(sessionId) }
        coVerify(exactly = 1) { settingsDao.getSettingsById(settingsId) }
        // Should not get to the final update due to validation failure
        coVerify(exactly = 0) { sessionDao.updateSessionCurrentSettingsId(any(), any()) }
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
        val daoError = SessionError.SessionNotFound(sessionId)
        coEvery { sessionDao.updateSessionLeafMessageId(sessionId, messageId) } returns daoError.left()

        // Act
        val result = sessionService.updateSessionLeafMessageId(sessionId, messageId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSessionLeafMessageIdError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as UpdateSessionLeafMessageIdError.SessionNotFound).id)
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
        assertTrue(error is UpdateSessionLeafMessageIdError.InvalidRelatedEntity, "Should be InvalidRelatedEntity error")
        assertEquals("Invalid message ID", (error as UpdateSessionLeafMessageIdError.InvalidRelatedEntity).message)
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
        val daoError = SessionError.SessionNotFound(sessionId)
        coEvery { sessionDao.deleteSession(sessionId) } returns daoError.left()

        // Act
        val result = sessionService.deleteSession(sessionId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is DeleteSessionError.SessionNotFound, "Should be SessionNotFound error")
        assertEquals(sessionId, (error as DeleteSessionError.SessionNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { sessionDao.deleteSession(sessionId) }
    }
}
