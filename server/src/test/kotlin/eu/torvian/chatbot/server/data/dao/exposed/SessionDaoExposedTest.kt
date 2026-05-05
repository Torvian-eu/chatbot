package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SessionDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [SessionDao]:
 * - Getting all sessions
 * - Getting a session by ID
 * - Inserting a new session
 * - Updating session properties (name, groupId, modelId, settingsId, leafMessageId)
 * - Deleting a session
 * - Ungrouping sessions
 * - Handling error cases (session not found)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class SessionDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var sessionDao: SessionDao
    private lateinit var groupDao: GroupDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testSession1 = TestDefaults.chatSession1
    private val testSession2 = TestDefaults.chatSession2
    private val testSessionLeaf1 = TestDefaults.sessionCurrentLeaf1

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        sessionDao = container.get()
        groupDao = container.get()
        testDataManager = container.get()

        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(TestDefaults.chatGroup1, TestDefaults.chatGroup2),
                llmModels = listOf(TestDefaults.llmModel1, TestDefaults.llmModel2),
                llmProviders = listOf(TestDefaults.llmProvider1, TestDefaults.llmProvider2),
                modelSettings = listOf(TestDefaults.modelSettings1, TestDefaults.modelSettings2),
            )
        )
        testDataManager.createTables(setOf(Table.CHAT_MESSAGES, Table.CHAT_SESSIONS, Table.SESSION_CURRENT_LEAF))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getAllSessions should return empty list when no sessions exist`() = runTest {
        val sessions = sessionDao.getAllSessions()
        assertTrue(sessions.isEmpty(), "Expected empty list when no sessions exist")
    }

    @Test
    fun `getAllSessions should return all sessions when sessions exist`() = runTest {
        // Insert test sessions
        testDataManager.insertChatSession(testSession1)
        testDataManager.insertChatSession(testSession2)

        // Get all sessions
        val sessions = sessionDao.getAllSessions()

        // Verify
        assertEquals(2, sessions.size, "Expected 2 sessions")
        assertTrue(
            sessions.any { it.name == testSession1.name },
            "Expected to find session with name ${testSession1.name}"
        )
        assertTrue(
            sessions.any { it.name == testSession2.name },
            "Expected to find session with name ${testSession2.name}"
        )
    }

    @Test
    fun `getSessionById should return session when it exists`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                chatSessions = listOf(testSession1),
                sessionCurrentLeaves = listOf(testSessionLeaf1),
                chatMessages = listOf(TestDefaults.chatMessage1, TestDefaults.chatMessage2)
            )
        )

        // Get the session by ID
        val result = sessionDao.getSessionById(testSession1.id)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing session")
        val session = result.getOrNull()
        assertNotNull(session, "Expected non-null session")
        assertEquals(testSession1.id, session.id, "Expected matching ID")
        assertEquals(testSession1.name, session.name, "Expected matching name")
        assertEquals(testSession1.groupId, session.groupId, "Expected matching groupId")
        assertEquals(testSession1.currentModelId, session.currentModelId, "Expected matching currentModelId")
        assertEquals(testSession1.currentSettingsId, session.currentSettingsId, "Expected matching currentSettingsId")
        assertEquals(
            testSessionLeaf1.messageId,
            session.currentLeafMessageId,
            "Expected matching currentLeafMessageId from the leaf table"
        )
        assertEquals(2, session.messages.size, "Expected 2 messages")
    }

    @Test
    fun `getSessionById should return SessionNotFound when session does not exist`() = runTest {
        // Insert a test session
        testDataManager.insertChatSession(testSession1)

        // Get a non-existent session
        val result = sessionDao.getSessionById(999)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent session")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertIs<SessionError.SessionNotFound>(error, "Expected SessionNotFound error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `insertSession should insert a new session`() = runTest {
        // Insert a new session
        val result = sessionDao.insertSession(name = testSession1.name)

        // Verify operation succeeded
        val session = result.getOrNull()
        assertNotNull(session, "Expected Right result for successful insertion")
        assertEquals(testSession1.name, session.name, "Expected matching name")
        assertNotNull(session.id, "Expected non-null ID")
        assertNotNull(session.createdAt, "Expected non-null createdAt")
        assertNotNull(session.updatedAt, "Expected non-null updatedAt")
        assertNull(session.groupId, "Expected null groupId")
        assertNull(session.currentModelId, "Expected null currentModelId")
        assertNull(session.currentSettingsId, "Expected null currentSettingsId")
        assertNull(session.currentLeafMessageId, "Expected null currentLeafMessageId")
        assertTrue(session.messages.isEmpty(), "Expected empty messages list")
    }

    @Test
    fun `insertSession should allow setting optional fields`() = runTest {
        // Insert a session with optional fields set
        val result = sessionDao.insertSession(
            name = testSession2.name,
            groupId = testSession2.groupId,
            currentModelId = testSession2.currentModelId,
            currentSettingsId = testSession2.currentSettingsId
        )

        // Verify
        val session = result.getOrNull()
        assertNotNull(session, "Expected Right result for successful insertion")
        assertEquals(testSession2.name, session.name, "Expected matching name")
        assertEquals(testSession2.groupId, session.groupId, "Expected matching groupId")
        assertEquals(testSession2.currentModelId, session.currentModelId, "Expected matching currentModelId")
        assertEquals(testSession2.currentSettingsId, session.currentSettingsId, "Expected matching currentSettingsId")
    }

    @Test
    fun `updateSessionName should rename an existing session`() = runTest {
        // Insert a session
        testDataManager.insertChatSession(testSession1)

        // Update the session name
        val newName = "Renamed Session"
        val result = sessionDao.updateSessionName(testSession1.id, newName)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for successful update")

        // Verify the session was updated
        val getResult = testDataManager.getChatSession(testSession1.id)
        assertNotNull(getResult, "Expected non-null session")
        assertEquals(getResult.name, newName, "Expected updated name")
    }

    @Test
    fun `updateSessionName should return SessionNotFound when session does not exist`() = runTest {
        // Insert a test session
        testDataManager.insertChatSession(testSession1)

        // Try to update a non-existent session
        val result = sessionDao.updateSessionName(999, "New Name")

        // Verify
        val error = result.leftOrNull()
        assertNotNull(error, "Expected Left result for non-existent session")
        assertIs<SessionError.SessionNotFound>(error, "Expected SessionNotFound error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `updateSessionGroupId should update group ID of an existing session`() = runTest {
        // Insert a session
        testDataManager.insertChatSession(testSession1)

        // Update the session group ID
        val newGroupId = testSession2.groupId
        val result = sessionDao.updateSessionGroupId(testSession1.id, newGroupId)

        // Verify operation succeeded
        assertTrue(result.isRight(), "Expected Right result for successful update")

        // Verify the session was updated
        val updatedSession = testDataManager.getChatSession(testSession1.id)
        assertNotNull(updatedSession, "Expected non-null session")
        assertEquals(newGroupId, updatedSession.groupId, "Expected updated group ID")
        assertNotEquals(testSession1.updatedAt, updatedSession.updatedAt, "Expected updated timestamp")
    }

    @Test
    fun `updateSessionCurrentModelId should update model ID of an existing session`() = runTest {
        // Insert a session
        testDataManager.insertChatSession(testSession1)

        // Update the session model ID
        val newModelId = testSession2.currentModelId
        val result = sessionDao.updateSessionCurrentModelId(testSession1.id, newModelId)

        // Verify operation succeeded
        assertTrue(result.isRight(), "Expected Right result for successful update")

        // Verify the session was updated
        val updatedSession = testDataManager.getChatSession(testSession1.id)
        assertNotNull(updatedSession, "Expected non-null session")
        assertEquals(newModelId, updatedSession.currentModelId, "Expected updated model ID")
        assertNotEquals(testSession1.updatedAt, updatedSession.updatedAt, "Expected updated timestamp")
    }

    @Test
    fun `updateSessionCurrentSettingsId should update settings ID of an existing session`() = runTest {
        // Insert a session
        testDataManager.insertChatSession(testSession1)

        // Update the session settings ID
        val newSettingsId = testSession2.currentSettingsId
        val result = sessionDao.updateSessionCurrentSettingsId(testSession1.id, newSettingsId)

        // Verify operation succeeded
        assertTrue(result.isRight(), "Expected Right result for successful update")

        // Verify the session was updated
        val updatedSession = testDataManager.getChatSession(testSession1.id)
        assertNotNull(updatedSession, "Expected non-null session")
        assertEquals(newSettingsId, updatedSession.currentSettingsId, "Expected updated settings ID")
        assertNotEquals(testSession1.updatedAt, updatedSession.updatedAt, "Expected updated timestamp")
    }

    @Test
    fun `updateSessionLeafMessageId should update leaf message ID of an existing session`() = runTest {
        // Setup test data
        testDataManager.setup(TestDataSet(
            chatSessions = listOf(testSession1),
            sessionCurrentLeaves = listOf(testSessionLeaf1),
            chatMessages = listOf(TestDefaults.chatMessage1, TestDefaults.chatMessage2)
        ))

        // Update the leaf message ID
        val newLeafMessageId = TestDefaults.chatMessage1.id
        val result = sessionDao.updateSessionLeafMessageId(testSession1.id, newLeafMessageId)

        // Verify operation succeeded
        assertTrue(result.isRight(), "Expected Right result for successful update")

        // Verify the session-leaf relationship was updated in the junction table
        val leafEntry = testDataManager.getSessionCurrentLeaf(testSession1.id)
        assertNotNull(leafEntry, "Expected non-null leaf entry")
        assertEquals(newLeafMessageId, leafEntry.messageId, "Expected updated leaf message ID")

        // Verify the session itself still exists and timestamp was updated
        val session = testDataManager.getChatSession(testSession1.id)
        assertNotNull(session, "Expected non-null session")
        assertNotEquals(testSession1.updatedAt, session.updatedAt, "Expected updated timestamp")
    }

    @Test
    fun `updateSessionLeafMessageId should clear leaf message ID when null is provided`() = runTest {
        // Setup test data
        testDataManager.setup(TestDataSet(
            chatSessions = listOf(testSession1),
            sessionCurrentLeaves = listOf(testSessionLeaf1),
            chatMessages = listOf(TestDefaults.chatMessage1, TestDefaults.chatMessage2)
        ))

        // Update the leaf message ID to null
        val result = sessionDao.updateSessionLeafMessageId(testSession1.id, null)

        // Verify operation succeeded
        assertTrue(result.isRight(), "Expected Right result for successful update")

        // Verify the leaf relationship was removed
        val leafAfter = testDataManager.getSessionCurrentLeaf(testSession1.id)
        assertNull(leafAfter, "Expected null leaf entry after clearing")
    }

    @Test
    fun `deleteSession should delete an existing session and its leaf reference`() = runTest {
        // Setup test data
        testDataManager.setup(TestDataSet(
            chatSessions = listOf(testSession1),
            sessionCurrentLeaves = listOf(testSessionLeaf1),
            chatMessages = listOf(TestDefaults.chatMessage1, TestDefaults.chatMessage2)
        ))

        // Delete the session
        val result = sessionDao.deleteSession(testSession1.id)

        // Verify deletion succeeded
        assertTrue(result.isRight(), "Expected Right result for successful deletion")

        // Verify the session was deleted
        val deletedSession = testDataManager.getChatSession(testSession1.id)
        assertNull(deletedSession, "Expected session to be deleted")

        // Verify the leaf reference was deleted (CASCADE should handle this)
        val deletedLeaf = testDataManager.getSessionCurrentLeaf(testSession1.id)
        assertNull(deletedLeaf, "Expected leaf reference to be deleted via CASCADE")
    }

    @Test
    fun `deleteSession should return SessionNotFound when session does not exist`() = runTest {
        // Insert a test session
        testDataManager.insertChatSession(testSession1)

        // Try to delete a non-existent session
        val result = sessionDao.deleteSession(999)

        // Verify
        val error = result.leftOrNull()
        assertNotNull(error, "Expected Left result for non-existent session")
        assertIs<SessionError.SessionNotFound>(error, "Expected SessionNotFound error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `ungroupSessions should ungroup all sessions assigned to a specific group`() = runTest {
        // Insert test sessions
        testDataManager.insertChatSession(testSession1)
        testDataManager.insertChatSession(testSession2.copy(groupId = testSession1.groupId))

        // Ungroup all sessions from this group
        sessionDao.ungroupSessions(testSession1.groupId!!)

        // Verify sessions are no longer assigned to the group
        val getResult1After = testDataManager.getChatSession(testSession1.id)
        val getResult2After = testDataManager.getChatSession(testSession2.id)
        assertNull(getResult1After?.groupId, "Session 1 should be ungrouped")
        assertNull(getResult2After?.groupId, "Session 2 should be ungrouped")
    }
}
