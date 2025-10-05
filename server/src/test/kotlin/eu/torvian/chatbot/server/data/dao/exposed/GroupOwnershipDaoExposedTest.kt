package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.GroupOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
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
import kotlin.test.assertTrue

/**
 * Tests for [GroupOwnershipDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [GroupOwnershipDao]:
 * - Getting all groups for a user
 * - Getting the owner of a group
 * - Setting ownership of a group
 * - Handling error cases (group not found, foreign key violations, unique constraint violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class GroupOwnershipDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var groupOwnershipDao: GroupOwnershipDao
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

    private val testGroup1 = ChatGroup(
        id = 1L,
        name = "Test Group 1",
        createdAt = TestDefaults.DEFAULT_INSTANT
    )

    private val testGroup2 = ChatGroup(
        id = 2L,
        name = "Test Group 2",
        createdAt = TestDefaults.DEFAULT_INSTANT
    )

    private val testGroup3 = ChatGroup(
        id = 3L,
        name = "Test Group 3",
        createdAt = TestDefaults.DEFAULT_INSTANT
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        groupOwnershipDao = container.get()
        testDataManager = container.get()

        // Set up test data with users and groups
        testDataManager.setup(
            TestDataSet(
                chatGroups = listOf(testGroup1, testGroup2, testGroup3)
            )
        )
        testDataManager.createTables(setOf(Table.USERS, Table.CHAT_GROUP_OWNERS))

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
    fun `getAllGroupsForUser should return empty list when user has no groups`() = runTest {
        val result = groupOwnershipDao.getAllGroupsForUser(testUser1.id)

        assertTrue(result.isEmpty(), "Should return empty list when user has no groups")
    }

    @Test
    fun `getAllGroupsForUser should return empty list when user does not exist`() = runTest {
        val nonExistentUserId = 999L
        val result = groupOwnershipDao.getAllGroupsForUser(nonExistentUserId)

        assertTrue(result.isEmpty(), "Should return empty list when user does not exist")
    }

    @Test
    fun `getAllGroupsForUser should return groups owned by user`() = runTest {
        // Set ownership for user1
        groupOwnershipDao.setOwner(testGroup1.id, testUser1.id)
        groupOwnershipDao.setOwner(testGroup3.id, testUser1.id)

        // Set ownership for user2
        groupOwnershipDao.setOwner(testGroup2.id, testUser2.id)

        val user1Groups = groupOwnershipDao.getAllGroupsForUser(testUser1.id)
        val user2Groups = groupOwnershipDao.getAllGroupsForUser(testUser2.id)

        assertEquals(2, user1Groups.size, "User1 should own 2 groups")
        assertEquals(1, user2Groups.size, "User2 should own 1 group")

        // Verify groups are returned in alphabetical order by name
        assertEquals("Test Group 1", user1Groups[0].name)
        assertEquals("Test Group 3", user1Groups[1].name)
        assertEquals("Test Group 2", user2Groups[0].name)

        // Verify group details
        assertEquals(testGroup1.id, user1Groups[0].id)
        assertEquals(testGroup3.id, user1Groups[1].id)
        assertEquals(testGroup2.id, user2Groups[0].id)
    }

    @Test
    fun `getOwner should return ResourceNotFound when group does not exist`() = runTest {
        val nonExistentGroupId = 999L
        val result = groupOwnershipDao.getOwner(nonExistentGroupId)

        assertEquals(GetOwnerError.ResourceNotFound(nonExistentGroupId.toString()).left(), result)
    }

    @Test
    fun `getOwner should return ResourceNotFound when group exists but has no owner`() = runTest {
        val result = groupOwnershipDao.getOwner(testGroup1.id)

        assertEquals(GetOwnerError.ResourceNotFound(testGroup1.id.toString()).left(), result)
    }

    @Test
    fun `getOwner should return owner user ID when group has owner`() = runTest {
        // Set ownership
        groupOwnershipDao.setOwner(testGroup1.id, testUser1.id)

        val result = groupOwnershipDao.getOwner(testGroup1.id)

        assertEquals(testUser1.id.right(), result)
    }

    @Test
    fun `setOwner should successfully create ownership link`() = runTest {
        val result = groupOwnershipDao.setOwner(testGroup1.id, testUser1.id)

        assertTrue(result.isRight(), "setOwner should succeed")

        // Verify ownership was set
        val ownerResult = groupOwnershipDao.getOwner(testGroup1.id)
        assertEquals(testUser1.id.right(), ownerResult)
    }

    @Test
    fun `setOwner should return AlreadyOwned when trying to set owner for already owned group`() = runTest {
        // Set initial ownership
        groupOwnershipDao.setOwner(testGroup1.id, testUser1.id)

        // Try to set ownership again (same user)
        val result1 = groupOwnershipDao.setOwner(testGroup1.id, testUser1.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result1)

        // Try to set ownership to different user
        val result2 = groupOwnershipDao.setOwner(testGroup1.id, testUser2.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result2)
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when group does not exist`() = runTest {
        val nonExistentGroupId = 999L
        val result = groupOwnershipDao.setOwner(nonExistentGroupId, testUser1.id)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentGroupId.toString(), testUser1.id).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when user does not exist`() = runTest {
        val nonExistentUserId = 999L
        val result = groupOwnershipDao.setOwner(testGroup1.id, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(testGroup1.id.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when both group and user do not exist`() = runTest {
        val nonExistentGroupId = 999L
        val nonExistentUserId = 888L
        val result = groupOwnershipDao.setOwner(nonExistentGroupId, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentGroupId.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `multiple users can own different groups`() = runTest {
        // Set different ownership
        groupOwnershipDao.setOwner(testGroup1.id, testUser1.id)
        groupOwnershipDao.setOwner(testGroup2.id, testUser2.id)
        groupOwnershipDao.setOwner(testGroup3.id, testUser1.id)

        // Verify ownership
        assertEquals(testUser1.id.right(), groupOwnershipDao.getOwner(testGroup1.id))
        assertEquals(testUser2.id.right(), groupOwnershipDao.getOwner(testGroup2.id))
        assertEquals(testUser1.id.right(), groupOwnershipDao.getOwner(testGroup3.id))

        // Verify user group lists
        val user1Groups = groupOwnershipDao.getAllGroupsForUser(testUser1.id)
        val user2Groups = groupOwnershipDao.getAllGroupsForUser(testUser2.id)

        assertEquals(2, user1Groups.size)
        assertEquals(1, user2Groups.size)

        val user1GroupIds = user1Groups.map { it.id }.toSet()
        val user2GroupIds = user2Groups.map { it.id }.toSet()

        assertEquals(setOf(testGroup1.id, testGroup3.id), user1GroupIds)
        assertEquals(setOf(testGroup2.id), user2GroupIds)
    }
}
