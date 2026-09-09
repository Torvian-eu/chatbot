package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.GroupDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.SessionProjectPair
import eu.torvian.chatbot.server.data.dao.SessionRolePair
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
        testDataManager.createTables(
            setOf(
                Table.CHAT_MESSAGES,
                Table.ASSISTANT_MESSAGES,
                Table.CHAT_SESSIONS,
                Table.SESSION_CURRENT_LEAF,
                // project_id on chat_sessions and agent_role_id reference these tables; the project
                // tests below seed project/role rows through the manager, so both tables must exist
                // (creation order is handled by the manager's mapping list: projects before sessions).
                Table.PROJECTS,
                Table.AGENT_ROLES
            )
        )
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
        assertEquals(testSession1.agentRoleId, session.agentRoleId, "Expected matching agentRoleId")
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
        assertNull(session.agentRoleId, "Expected null agentRoleId")
        assertNull(session.currentLeafMessageId, "Expected null currentLeafMessageId")
        assertTrue(session.messages.isEmpty(), "Expected empty messages list")
    }

    @Test
    fun `insertSession should allow setting optional fields`() = runTest {
        // Insert a session with optional fields set
        val result = sessionDao.insertSession(
            name = testSession2.name,
            groupId = testSession2.groupId,
            agentRoleId = testSession2.agentRoleId
        )

        // Verify
        val session = result.getOrNull()
        assertNotNull(session, "Expected Right result for successful insertion")
        assertEquals(testSession2.name, session.name, "Expected matching name")
        assertEquals(testSession2.groupId, session.groupId, "Expected matching groupId")
        assertEquals(testSession2.agentRoleId, session.agentRoleId, "Expected matching agentRoleId")
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
        assertEquals(newName, getResult.name, "Expected updated name")
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
    fun `updateSessionAgentRoleId should update agent role ID of an existing session`() = runTest {
        // Insert a session
        testDataManager.insertChatSession(testSession1)

        // Update the session agent role ID
        val newAgentRoleId = testSession2.agentRoleId
        val result = sessionDao.updateSessionAgentRoleId(testSession1.id, newAgentRoleId)

        // Verify operation succeeded
        assertTrue(result.isRight(), "Expected Right result for successful update")

        // Verify the session was updated
        val updatedSession = testDataManager.getChatSession(testSession1.id)
        assertNotNull(updatedSession, "Expected non-null session")
        assertEquals(newAgentRoleId, updatedSession.agentRoleId, "Expected updated agent role ID")
        assertNotEquals(testSession1.updatedAt, updatedSession.updatedAt, "Expected updated timestamp")
    }

    @Test
    fun `updateSessionAgentRoleId should return SessionNotFound when session does not exist`() = runTest {
        // Insert a session
        testDataManager.insertChatSession(testSession1)

        // Try to update a non-existent session
        val result = sessionDao.updateSessionAgentRoleId(999, null)

        // Verify
        val error = result.leftOrNull()
        assertNotNull(error, "Expected Left result for non-existent session")
        assertIs<SessionError.SessionNotFound>(error, "Expected SessionNotFound error")
        assertEquals(999, error.id, "Expected error with correct ID")
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

    // --- Project selection (V28 / Sessions feature) ---

    // Role fixture with null model/settings so the session tests below need no llm seeding.
    private val sessionTestRole = TestDefaults.agentRole1.copy(
        id = 50L,
        modelId = null,
        modelSettingsId = null,
        instructionsJson = "[]"
    )

    @Test
    fun `insertSession persists a project selection on the session`() = runTest {
        val project = TestDefaults.project1
        testDataManager.insertProject(project)

        val result = sessionDao.insertSession(
            name = "Project session",
            projectId = project.id
        )

        val session = result.getOrNull()
        assertNotNull(session, "Expected Right result for successful insertion")
        assertEquals(project.id, session.projectId, "Expected the persisted project id")
    }

    @Test
    fun `getSessionById returns the persisted projectId`() = runTest {
        val project = TestDefaults.project1
        testDataManager.insertProject(project)
        testDataManager.insertChatSession(testSession1.copy(projectId = project.id))

        val result = sessionDao.getSessionById(testSession1.id)

        assertTrue(result.isRight())
        assertEquals(project.id, result.getOrNull()?.projectId, "Expected the persisted project id")
    }

    @Test
    fun `updateSessionProjectId sets and clears the project selection`() = runTest {
        val project = TestDefaults.project1
        testDataManager.insertProject(project)
        testDataManager.insertChatSession(testSession1)

        val setResult = sessionDao.updateSessionProjectId(testSession1.id, project.id)
        assertTrue(setResult.isRight())
        assertEquals(
            project.id,
            testDataManager.getChatSession(testSession1.id)?.projectId,
            "project id must be persisted"
        )

        val clearResult = sessionDao.updateSessionProjectId(testSession1.id, null)
        assertTrue(clearResult.isRight())
        assertNull(
            testDataManager.getChatSession(testSession1.id)?.projectId,
            "null project id must clear the selection"
        )
    }

    @Test
    fun `updateSessionProjectId returns SessionNotFound for a missing session`() = runTest {
        testDataManager.insertChatSession(testSession1)

        val result = sessionDao.updateSessionProjectId(999, TestDefaults.project1.id)

        val error = result.leftOrNull()
        assertNotNull(error, "Expected Left result for non-existent session")
        assertIs<SessionError.SessionNotFound>(error, "Expected SessionNotFound error")
    }

    @Test
    fun `updateSessionProjectId returns ForeignKeyViolation for a foreign project`() = runTest {
        testDataManager.insertChatSession(testSession1)

        val result = sessionDao.updateSessionProjectId(testSession1.id, 999)

        val error = result.leftOrNull()
        assertNotNull(error, "Expected Left result for a foreign project id")
        assertIs<SessionError.ForeignKeyViolation>(error, "Expected ForeignKeyViolation error")
    }

    @Test
    fun `getSessionProjectSelection reads the project and agent role without messages`() = runTest {
        val project = TestDefaults.project1
        testDataManager.insertProject(project)
        testDataManager.insertAgentRole(sessionTestRole)
        testDataManager.insertChatSession(
            testSession1.copy(agentRoleId = sessionTestRole.id, projectId = project.id)
        )

        val result = sessionDao.getSessionProjectSelection(testSession1.id)

        assertTrue(result.isRight())
        val selection = result.getOrNull()
        assertNotNull(selection, "Expected non-null selection")
        assertEquals(project.id, selection.projectId)
        assertEquals(sessionTestRole.id, selection.agentRoleId)
    }

    @Test
    fun `getSessionProjectSelection returns SessionNotFound for a missing session`() = runTest {
        val result = sessionDao.getSessionProjectSelection(999)

        assertIs<SessionError.SessionNotFound>(result.leftOrNull())
    }

    @Test
    fun `getSessionProjectPairsForRole returns sessions using the role with their project ids`() = runTest {
        val project = TestDefaults.project1
        testDataManager.insertProject(project)
        testDataManager.insertAgentRole(sessionTestRole)
        testDataManager.insertChatSession(
            testSession1.copy(agentRoleId = sessionTestRole.id, projectId = project.id)
        )
        // A project-less session using the same role (illegal under the legality rule, but the DAO read must
        // still surface the pair so the service sweep can detect it).
        testDataManager.insertChatSession(
            testSession2.copy(agentRoleId = sessionTestRole.id, projectId = null)
        )

        val pairs = sessionDao.getSessionProjectPairsForRole(sessionTestRole.id)

        assertEquals(2, pairs.size)
        assertEquals(
            setOf(SessionProjectPair(testSession1.id, project.id), SessionProjectPair(testSession2.id, null)),
            pairs.toSet()
        )
    }

    @Test
    fun `getSessionProjectPairsForRoles batch-reads sessions using any of the roles`() = runTest {
        val project = TestDefaults.project1
        val roleA = sessionTestRole.copy(id = 70L)
        val roleB = sessionTestRole.copy(id = 71L)
        val roleUnused = sessionTestRole.copy(id = 72L)
        testDataManager.insertProject(project)
        testDataManager.insertAgentRole(roleA)
        testDataManager.insertAgentRole(roleB)
        testDataManager.insertAgentRole(roleUnused)
        testDataManager.insertChatSession(
            testSession1.copy(agentRoleId = roleA.id, projectId = project.id)
        )
        // A project-less session using role B surfaces with a null project id (the project-side
        // attach sweep must clear exactly this kind of pair).
        testDataManager.insertChatSession(
            testSession2.copy(agentRoleId = roleB.id, projectId = null)
        )
        // A session using a role OUTSIDE the queried set must not be returned.
        testDataManager.insertChatSession(
            TestDefaults.chatSession1.copy(id = 77L, agentRoleId = roleUnused.id, projectId = null)
        )

        // The batch read covers both queried roles in one call; each session appears at most once.
        val pairs = sessionDao.getSessionProjectPairsForRoles(listOf(roleA.id, roleB.id))

        assertEquals(2, pairs.size)
        assertEquals(
            setOf(SessionProjectPair(testSession1.id, project.id), SessionProjectPair(testSession2.id, null)),
            pairs.toSet()
        )
    }

    @Test
    fun `getSessionProjectPairsForRoles returns an empty list for an empty role set`() = runTest {
        testDataManager.insertChatSession(testSession1)

        val pairs = sessionDao.getSessionProjectPairsForRoles(emptyList())

        assertTrue(pairs.isEmpty(), "an empty role set must produce no pairs and no SQL")
    }

    @Test
    fun `clearAgentRoleForSessions clears the role and bumps updatedAt`() = runTest {
        testDataManager.insertAgentRole(sessionTestRole)
        testDataManager.insertChatSession(testSession1.copy(agentRoleId = sessionTestRole.id))
        testDataManager.insertChatSession(testSession2.copy(agentRoleId = sessionTestRole.id))

        sessionDao.clearAgentRoleForSessions(listOf(testSession1.id, testSession2.id))

        val session1After = testDataManager.getChatSession(testSession1.id)
        val session2After = testDataManager.getChatSession(testSession2.id)
        assertNull(session1After?.agentRoleId, "Session 1 role must be cleared")
        assertNull(session2After?.agentRoleId, "Session 2 role must be cleared")
        assertNotEquals(testSession1.updatedAt, session1After?.updatedAt, "updatedAt must be bumped")
    }

    @Test
    fun `clearAgentRoleForSessions is a no-op on an empty list`() = runTest {
        testDataManager.insertChatSession(testSession1.copy(agentRoleId = null))

        // Must not throw and must not touch any session.
        sessionDao.clearAgentRoleForSessions(emptyList())

        assertEquals(
            testSession1.updatedAt,
            testDataManager.getChatSession(testSession1.id)?.updatedAt,
            "no session may be touched by an empty sweep"
        )
    }

    @Test
    fun `getSessionIdsByProject returns sessions selecting the project`() = runTest {
        val project = TestDefaults.project1
        testDataManager.insertProject(project)
        testDataManager.insertChatSession(testSession1.copy(projectId = project.id))
        testDataManager.insertChatSession(testSession2.copy(projectId = project.id))
        testDataManager.insertChatSession(TestDefaults.chatSession1.copy(id = 77L, projectId = null))

        val ids = sessionDao.getSessionIdsByProject(project.id)

        assertEquals(setOf(testSession1.id, testSession2.id), ids.toSet())
    }

    @Test
    fun `getSessionRolePairsForProject returns sessions with their attached roles`() = runTest {
        val project = TestDefaults.project1
        val otherProject = TestDefaults.project2
        val roleA = sessionTestRole.copy(id = 60L)
        val roleB = sessionTestRole.copy(id = 61L)
        testDataManager.insertProject(project)
        testDataManager.insertProject(otherProject)
        testDataManager.insertAgentRole(roleA)
        testDataManager.insertAgentRole(roleB)
        testDataManager.insertChatSession(testSession1.copy(projectId = project.id, agentRoleId = roleA.id))
        testDataManager.insertChatSession(testSession2.copy(projectId = project.id, agentRoleId = roleB.id))
        // A role-less session selecting the project must surface with a null role.
        testDataManager.insertChatSession(
            TestDefaults.chatSession1.copy(id = 77L, projectId = project.id, agentRoleId = null)
        )
        // A session selecting a DIFFERENT project must not be returned.
        testDataManager.insertChatSession(
            TestDefaults.chatSession1.copy(id = 78L, projectId = otherProject.id, agentRoleId = roleA.id)
        )

        val pairs = sessionDao.getSessionRolePairsForProject(project.id)

        assertEquals(
            setOf(
                SessionRolePair(testSession1.id, roleA.id),
                SessionRolePair(testSession2.id, roleB.id),
                SessionRolePair(77L, null)
            ),
            pairs.toSet()
        )
    }
}
