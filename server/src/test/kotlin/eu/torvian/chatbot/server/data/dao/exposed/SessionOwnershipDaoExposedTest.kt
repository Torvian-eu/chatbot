package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.UserStatus
import eu.torvian.chatbot.server.data.dao.SessionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [SessionOwnershipDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [SessionOwnershipDao]:
 * - Getting all session summaries for a user
 * - Getting the owner of a session
 * - Setting ownership of a session
 * - Handling error cases (session not found, foreign key violations, unique constraint violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class SessionOwnershipDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var sessionOwnershipDao: SessionOwnershipDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testUser1 = UserEntity(
        id = 1L,
        username = "testuser1",
        passwordHash = "hashedpassword1",
        email = "test1@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        updatedAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        lastLogin = null
    )

    private val testUser2 = UserEntity(
        id = 2L,
        username = "testuser2",
        passwordHash = "hashedpassword2",
        email = "test2@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        updatedAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        lastLogin = null
    )

    private val testSession1 = ChatSessionEntity(
        id = 1L,
        name = "Test Session 1",
        createdAt = TestDefaults.DEFAULT_INSTANT,
        updatedAt = TestDefaults.DEFAULT_INSTANT,
        groupId = TestDefaults.chatGroup1.id,
        currentModelId = TestDefaults.llmModel1.id,
        currentSettingsId = TestDefaults.modelSettings1.id
    )

    private val testSession2 = ChatSessionEntity(
        id = 2L,
        name = "Test Session 2",
        createdAt = TestDefaults.DEFAULT_INSTANT.plus(1.hours), // 1 hour later
        updatedAt = TestDefaults.DEFAULT_INSTANT.plus(1.hours),
        groupId = TestDefaults.chatGroup2.id,
        currentModelId = TestDefaults.llmModel2.id,
        currentSettingsId = TestDefaults.modelSettings2.id
    )

    private val testSession3 = ChatSessionEntity(
        id = 3L,
        name = "Test Session 3",
        createdAt = TestDefaults.DEFAULT_INSTANT.plus(30.minutes), // 30 minutes later
        updatedAt = TestDefaults.DEFAULT_INSTANT.plus(2.hours), // 2 hours later (most recent update)
        groupId = null, // Ungrouped session
        currentModelId = TestDefaults.llmModel1.id,
        currentSettingsId = TestDefaults.modelSettings1.id
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        sessionOwnershipDao = container.get()
        testDataManager = container.get()

        // Set up test data with users, groups, models, settings, and sessions
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(TestDefaults.chatGroup1, TestDefaults.chatGroup2),
                llmModels = listOf(TestDefaults.llmModel1, TestDefaults.llmModel2),
                llmProviders = listOf(TestDefaults.llmProvider1, TestDefaults.llmProvider2),
                modelSettings = listOf(TestDefaults.modelSettings1, TestDefaults.modelSettings2),
                chatSessions = listOf(testSession1, testSession2, testSession3)
            )
        )
        testDataManager.createTables(setOf(Table.USERS, Table.CHAT_SESSION_OWNERS))

        // Insert test users
        testDataManager.insertUser(testUser1)
        testDataManager.insertUser(testUser2)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getAllSessionsForUser should return empty list when user has no sessions`() = runTest {
        val result = sessionOwnershipDao.getAllSessionsForUser(testUser1.id)

        assertTrue(result.isEmpty(), "Should return empty list when user has no sessions")
    }

    @Test
    fun `getAllSessionsForUser should return empty list when user does not exist`() = runTest {
        val nonExistentUserId = 999L
        val result = sessionOwnershipDao.getAllSessionsForUser(nonExistentUserId)

        assertTrue(result.isEmpty(), "Should return empty list when user does not exist")
    }

    @Test
    fun `getAllSessionsForUser should return sessions owned by user ordered by updatedAt desc`() = runTest {
        // Set ownership for user1
        sessionOwnershipDao.setOwner(testSession1.id, testUser1.id)
        sessionOwnershipDao.setOwner(testSession3.id, testUser1.id)

        // Set ownership for user2
        sessionOwnershipDao.setOwner(testSession2.id, testUser2.id)

        val user1Sessions = sessionOwnershipDao.getAllSessionsForUser(testUser1.id)
        val user2Sessions = sessionOwnershipDao.getAllSessionsForUser(testUser2.id)

        assertEquals(2, user1Sessions.size, "User1 should own 2 sessions")
        assertEquals(1, user2Sessions.size, "User2 should own 1 session")

        // Verify sessions are returned in descending order by updatedAt (most recent first)
        assertEquals(testSession3.id, user1Sessions[0].id, "Session 3 should be first (most recent update)")
        assertEquals(testSession1.id, user1Sessions[1].id, "Session 1 should be second")
        assertEquals(testSession2.id, user2Sessions[0].id)

        // Verify session summary details
        val session3Summary = user1Sessions[0]
        assertEquals(testSession3.name, session3Summary.name)
        assertEquals(testSession3.createdAt, session3Summary.createdAt)
        assertEquals(testSession3.updatedAt, session3Summary.updatedAt)
        assertEquals(testSession3.groupId, session3Summary.groupId)
        assertNull(session3Summary.groupName, "Ungrouped session should have null groupName")

        val session1Summary = user1Sessions[1]
        assertEquals(testSession1.name, session1Summary.name)
        assertEquals(testSession1.groupId, session1Summary.groupId)
        assertEquals(TestDefaults.chatGroup1.name, session1Summary.groupName)
    }

    @Test
    fun `getOwner should return ResourceNotFound when session does not exist`() = runTest {
        val nonExistentSessionId = 999L
        val result = sessionOwnershipDao.getOwner(nonExistentSessionId)

        assertEquals(GetOwnerError.ResourceNotFound(nonExistentSessionId.toString()).left(), result)
    }

    @Test
    fun `getOwner should return ResourceNotFound when session exists but has no owner`() = runTest {
        val result = sessionOwnershipDao.getOwner(testSession1.id)

        assertEquals(GetOwnerError.ResourceNotFound(testSession1.id.toString()).left(), result)
    }

    @Test
    fun `getOwner should return owner user ID when session has owner`() = runTest {
        // Set ownership
        sessionOwnershipDao.setOwner(testSession1.id, testUser1.id)

        val result = sessionOwnershipDao.getOwner(testSession1.id)

        assertEquals(testUser1.id.right(), result)
    }

    @Test
    fun `setOwner should successfully create ownership link`() = runTest {
        val result = sessionOwnershipDao.setOwner(testSession1.id, testUser1.id)

        assertTrue(result.isRight(), "setOwner should succeed")

        // Verify ownership was set
        val ownerResult = sessionOwnershipDao.getOwner(testSession1.id)
        assertEquals(testUser1.id.right(), ownerResult)
    }

    @Test
    fun `setOwner should return AlreadyOwned when trying to set owner for already owned session`() = runTest {
        // Set initial ownership
        sessionOwnershipDao.setOwner(testSession1.id, testUser1.id)

        // Try to set ownership again (same user)
        val result1 = sessionOwnershipDao.setOwner(testSession1.id, testUser1.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result1)

        // Try to set ownership to different user
        val result2 = sessionOwnershipDao.setOwner(testSession1.id, testUser2.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result2)
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when session does not exist`() = runTest {
        val nonExistentSessionId = 999L
        val result = sessionOwnershipDao.setOwner(nonExistentSessionId, testUser1.id)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentSessionId.toString(), testUser1.id).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when user does not exist`() = runTest {
        val nonExistentUserId = 999L
        val result = sessionOwnershipDao.setOwner(testSession1.id, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(testSession1.id.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when both session and user do not exist`() = runTest {
        val nonExistentSessionId = 999L
        val nonExistentUserId = 888L
        val result = sessionOwnershipDao.setOwner(nonExistentSessionId, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentSessionId.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `multiple users can own different sessions`() = runTest {
        // Set different ownership
        sessionOwnershipDao.setOwner(testSession1.id, testUser1.id)
        sessionOwnershipDao.setOwner(testSession2.id, testUser2.id)
        sessionOwnershipDao.setOwner(testSession3.id, testUser1.id)

        // Verify ownership
        assertEquals(testUser1.id.right(), sessionOwnershipDao.getOwner(testSession1.id))
        assertEquals(testUser2.id.right(), sessionOwnershipDao.getOwner(testSession2.id))
        assertEquals(testUser1.id.right(), sessionOwnershipDao.getOwner(testSession3.id))

        // Verify user session lists
        val user1Sessions = sessionOwnershipDao.getAllSessionsForUser(testUser1.id)
        val user2Sessions = sessionOwnershipDao.getAllSessionsForUser(testUser2.id)

        assertEquals(2, user1Sessions.size)
        assertEquals(1, user2Sessions.size)

        val user1SessionIds = user1Sessions.map { it.id }.toSet()
        val user2SessionIds = user2Sessions.map { it.id }.toSet()

        assertEquals(setOf(testSession1.id, testSession3.id), user1SessionIds)
        assertEquals(setOf(testSession2.id), user2SessionIds)
    }
}
