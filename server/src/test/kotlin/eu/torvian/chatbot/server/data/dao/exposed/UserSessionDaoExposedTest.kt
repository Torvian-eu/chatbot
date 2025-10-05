package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserSessionDao
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [UserSessionDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [UserSessionDao]:
 * - Getting a session by ID
 * - Getting sessions by user ID
 * - Inserting a new session
 * - Updating last accessed time
 * - Deleting a session
 * - Deleting sessions by user ID
 * - Deleting expired sessions
 * - Handling error cases (session not found, foreign key violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class UserSessionDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var userDao: UserDao
    private lateinit var userSessionDao: UserSessionDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private lateinit var testUser: UserEntity
    private val futureTime = System.currentTimeMillis() + 3600000 // 1 hour from now
    private val pastTime = System.currentTimeMillis() - 3600000 // 1 hour ago

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        userDao = container.get()
        userSessionDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(setOf(Table.USERS, Table.USER_SESSIONS))

        // Create a test user for session tests
        val userResult = userDao.insertUser("testuser", "hashedpassword", "test@example.com", UserStatus.ACTIVE)
        assertTrue(userResult.isRight(), "Failed to create test user")
        testUser = userResult.getOrNull()!!
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getSessionById should return session when it exists`() = runTest {
        // Insert a test session
        val insertResult = userSessionDao.insertSession(testUser.id, futureTime)
        assertTrue(insertResult.isRight(), "Failed to insert test session")
        val insertedSession = insertResult.getOrNull()!!

        // Get the session by ID
        val result = userSessionDao.getSessionById(insertedSession.id)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing session")
        val session = result.getOrNull()
        assertNotNull(session, "Expected non-null session")
        assertEquals(insertedSession.id, session.id, "Expected matching ID")
        assertEquals(testUser.id, session.userId, "Expected matching user ID")
        assertEquals(futureTime, session.expiresAt.toEpochMilliseconds(), "Expected matching expiration time")
    }

    @Test
    fun `getSessionById should return SessionNotFound when session does not exist`() = runTest {
        val result = userSessionDao.getSessionById(999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing session")
        val error = result.leftOrNull()
        assertTrue(error is UserSessionError.SessionNotFound, "Expected SessionNotFound error")
        assertEquals(999L, (error as UserSessionError.SessionNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `getSessionsByUserId should return all sessions for user`() = runTest {
        // Insert multiple sessions for the user
        val session1Result = userSessionDao.insertSession(testUser.id, futureTime)
        val session2Result = userSessionDao.insertSession(testUser.id, futureTime + 1000)
        assertTrue(session1Result.isRight() && session2Result.isRight(), "Failed to insert test sessions")

        // Get sessions by user ID
        val sessions = userSessionDao.getSessionsByUserId(testUser.id)

        // Verify
        assertEquals(2, sessions.size, "Expected 2 sessions for user")
        assertTrue(sessions.all { it.userId == testUser.id }, "All sessions should belong to the test user")
    }

    @Test
    fun `getSessionsByUserId should return empty list when user has no sessions`() = runTest {
        val sessions = userSessionDao.getSessionsByUserId(testUser.id)
        assertTrue(sessions.isEmpty(), "Expected empty list when user has no sessions")
    }

    @Test
    fun `insertSession should create new session successfully`() = runTest {
        val result = userSessionDao.insertSession(testUser.id, futureTime)

        assertTrue(result.isRight(), "Expected successful session creation")
        val session = result.getOrNull()
        assertNotNull(session, "Expected non-null session")
        assertEquals(testUser.id, session.userId, "Expected matching user ID")
        assertEquals(futureTime, session.expiresAt.toEpochMilliseconds(), "Expected matching expiration time")
        assertNotNull(session.createdAt, "Expected non-null createdAt")
        assertNotNull(session.lastAccessed, "Expected non-null lastAccessed")
    }

    @Test
    fun `insertSession should return ForeignKeyViolation when user does not exist`() = runTest {
        val result = userSessionDao.insertSession(999L, futureTime)

        assertTrue(result.isLeft(), "Expected Left result for non-existing user")
        val error = result.leftOrNull()
        assertTrue(error is UserSessionError.ForeignKeyViolation, "Expected ForeignKeyViolation error")
    }

    @Test
    fun `updateLastAccessed should update timestamp successfully`() = runTest {
        // Insert a test session
        val insertResult = userSessionDao.insertSession(testUser.id, futureTime)
        assertTrue(insertResult.isRight(), "Failed to insert test session")
        val session = insertResult.getOrNull()!!

        // Update last accessed
        val newAccessTime = System.currentTimeMillis()
        val updateResult = userSessionDao.updateLastAccessed(session.id, newAccessTime)

        assertTrue(updateResult.isRight(), "Expected successful last accessed update")

        // Verify the update
        val fetchResult = userSessionDao.getSessionById(session.id)
        assertTrue(fetchResult.isRight(), "Failed to fetch updated session")
        val fetchedSession = fetchResult.getOrNull()!!
        assertEquals(
            newAccessTime,
            fetchedSession.lastAccessed.toEpochMilliseconds(),
            "Expected updated last accessed time"
        )
    }

    @Test
    fun `updateLastAccessed should return SessionNotFound when session does not exist`() = runTest {
        val result = userSessionDao.updateLastAccessed(999L, System.currentTimeMillis())

        assertTrue(result.isLeft(), "Expected Left result for non-existing session")
        val error = result.leftOrNull()
        assertTrue(error is UserSessionError.SessionNotFound, "Expected SessionNotFound error")
        assertEquals(999L, (error as UserSessionError.SessionNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `deleteSession should delete existing session successfully`() = runTest {
        // Insert a test session
        val insertResult = userSessionDao.insertSession(testUser.id, futureTime)
        assertTrue(insertResult.isRight(), "Failed to insert test session")
        val session = insertResult.getOrNull()!!

        // Delete the session
        val deleteResult = userSessionDao.deleteSession(session.id)

        assertTrue(deleteResult.isRight(), "Expected successful session deletion")

        // Verify the deletion
        val fetchResult = userSessionDao.getSessionById(session.id)
        assertTrue(fetchResult.isLeft(), "Expected session to be deleted")
        val error = fetchResult.leftOrNull()
        assertTrue(error is UserSessionError.SessionNotFound, "Expected SessionNotFound error after deletion")
    }

    @Test
    fun `deleteSession should return SessionNotFound when session does not exist`() = runTest {
        val result = userSessionDao.deleteSession(999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing session")
        val error = result.leftOrNull()
        assertTrue(error is UserSessionError.SessionNotFound, "Expected SessionNotFound error")
        assertEquals(999L, (error as UserSessionError.SessionNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `deleteSessionsByUserId should delete all sessions for user`() = runTest {
        // Insert multiple sessions for the user
        userSessionDao.insertSession(testUser.id, futureTime)
        userSessionDao.insertSession(testUser.id, futureTime + 1000)

        // Delete all sessions for the user
        val deletedCount = userSessionDao.deleteSessionsByUserId(testUser.id)

        assertEquals(2, deletedCount, "Expected 2 sessions to be deleted")

        // Verify all sessions are deleted
        val remainingSessions = userSessionDao.getSessionsByUserId(testUser.id)
        assertTrue(remainingSessions.isEmpty(), "Expected no remaining sessions for user")
    }

    @Test
    fun `deleteExpiredSessions should delete only expired sessions`() = runTest {
        // Insert both expired and valid sessions
        userSessionDao.insertSession(testUser.id, pastTime) // Expired
        userSessionDao.insertSession(testUser.id, futureTime) // Valid

        val currentTime = System.currentTimeMillis()
        val deletedCount = userSessionDao.deleteExpiredSessions(currentTime)

        assertEquals(1, deletedCount, "Expected 1 expired session to be deleted")

        // Verify only the valid session remains
        val remainingSessions = userSessionDao.getSessionsByUserId(testUser.id)
        assertEquals(1, remainingSessions.size, "Expected 1 remaining session")
        assertTrue(
            remainingSessions[0].expiresAt.toEpochMilliseconds() > currentTime,
            "Remaining session should not be expired"
        )
    }
}
