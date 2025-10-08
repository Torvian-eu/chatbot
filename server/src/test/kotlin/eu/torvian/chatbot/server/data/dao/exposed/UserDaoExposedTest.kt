package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.models.user.UserWithDetails
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.entities.UserRoleAssignmentEntity
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
import kotlin.test.*

/**
 * Tests for [UserDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [UserDao]:
 * - Getting all users
 * - Getting a user by ID
 * - Getting a user by username
 * - Inserting a new user
 * - Updating a user
 * - Updating last login
 * - Deleting a user
 * - Handling error cases (user not found, unique constraint violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class UserDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var userDao: UserDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testUser1 = UserEntity(
        id = 1,
        username = "testuser1",
        passwordHash = "hashedpassword1",
        email = "test1@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        updatedAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        lastLogin = null
    )

    private val testUser2 = UserEntity(
        id = 2,
        username = "testuser2",
        passwordHash = "hashedpassword2",
        email = "test2@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        updatedAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        lastLogin = null
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        userDao = container.get()
        testDataManager = container.get()

        // Create tables required for user details queries (roles, assignments, groups)
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.ROLES,
                Table.USER_ROLE_ASSIGNMENTS,
                Table.USER_GROUPS,
                Table.USER_GROUP_MEMBERSHIPS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getAllUsers should return empty list when no users exist`() = runTest {
        val users = userDao.getAllUsers()
        assertTrue(users.isEmpty(), "Expected empty list when no users exist")
    }

    @Test
    fun `getAllUsers should return all users when users exist`() = runTest {
        // Insert test users
        userDao.insertUser(testUser1.username, testUser1.passwordHash, testUser1.email, UserStatus.ACTIVE)
        userDao.insertUser(testUser2.username, testUser2.passwordHash, testUser2.email, UserStatus.ACTIVE)

        // Get all users
        val users = userDao.getAllUsers()

        // Verify
        assertEquals(2, users.size, "Expected 2 users")
        assertTrue(
            users.any { it.username == testUser1.username },
            "Expected to find user with username ${testUser1.username}"
        )
        assertTrue(
            users.any { it.username == testUser2.username },
            "Expected to find user with username ${testUser2.username}"
        )
    }

    @Test
    fun `getUserById should return user when it exists`() = runTest {
        // Insert a test user
        val insertResult =
            userDao.insertUser(testUser1.username, testUser1.passwordHash, testUser1.email, UserStatus.ACTIVE)
        assertTrue(insertResult.isRight(), "Failed to insert test user")
        val insertedUser = insertResult.getOrNull()!!

        // Get the user by ID
        val result = userDao.getUserById(insertedUser.id)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing user")
        val user = result.getOrNull()
        assertNotNull(user, "Expected non-null user")
        assertEquals(insertedUser.id, user.id, "Expected matching ID")
        assertEquals(testUser1.username, user.username, "Expected matching username")
        assertEquals(testUser1.passwordHash, user.passwordHash, "Expected matching password hash")
        assertEquals(testUser1.email, user.email, "Expected matching email")
    }

    @Test
    fun `getUserById should return UserNotFound when user does not exist`() = runTest {
        val result = userDao.getUserById(999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing user")
        val error = result.leftOrNull()
        assertTrue(error is UserError.UserNotFound, "Expected UserNotFound error")
        assertEquals(999L, (error as UserError.UserNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `getUserByUsername should return user when it exists`() = runTest {
        // Insert a test user
        val insertResult =
            userDao.insertUser(testUser1.username, testUser1.passwordHash, testUser1.email, UserStatus.ACTIVE)
        assertTrue(insertResult.isRight(), "Failed to insert test user")

        // Get the user by username
        val result = userDao.getUserByUsername(testUser1.username)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing user")
        val user = result.getOrNull()
        assertNotNull(user, "Expected non-null user")
        assertEquals(testUser1.username, user.username, "Expected matching username")
        assertEquals(testUser1.passwordHash, user.passwordHash, "Expected matching password hash")
        assertEquals(testUser1.email, user.email, "Expected matching email")
    }

    @Test
    fun `getUserByUsername should return UserNotFoundByUsername when user does not exist`() = runTest {
        val result = userDao.getUserByUsername("nonexistent")

        assertTrue(result.isLeft(), "Expected Left result for non-existing user")
        val error = result.leftOrNull()
        assertTrue(error is UserError.UserNotFoundByUsername, "Expected UserNotFoundByUsername error")
        assertEquals(
            "nonexistent",
            (error as UserError.UserNotFoundByUsername).username,
            "Expected matching username in error"
        )
    }

    @Test
    fun `insertUser should create new user successfully`() = runTest {
        val result = userDao.insertUser(testUser1.username, testUser1.passwordHash, testUser1.email, UserStatus.ACTIVE)

        assertTrue(result.isRight(), "Expected successful user creation")
        val user = result.getOrNull()
        assertNotNull(user, "Expected non-null user")
        assertEquals(testUser1.username, user.username, "Expected matching username")
        assertEquals(testUser1.passwordHash, user.passwordHash, "Expected matching password hash")
        assertEquals(testUser1.email, user.email, "Expected matching email")
        assertNotNull(user.createdAt, "Expected non-null createdAt")
        assertNotNull(user.updatedAt, "Expected non-null updatedAt")
        assertNull(user.lastLogin, "Expected null lastLogin for new user")
    }

    @Test
    fun `insertUser should return UsernameAlreadyExists when username is taken`() = runTest {
        // Insert first user
        val firstResult =
            userDao.insertUser(testUser1.username, testUser1.passwordHash, testUser1.email, UserStatus.ACTIVE)
        assertTrue(firstResult.isRight(), "Failed to insert first user")

        // Try to insert user with same username
        val secondResult =
            userDao.insertUser(testUser1.username, "differenthash", "different@example.com", UserStatus.ACTIVE)

        assertTrue(secondResult.isLeft(), "Expected Left result for duplicate username")
        val error = secondResult.leftOrNull()
        assertTrue(error is UserError.UsernameAlreadyExists, "Expected UsernameAlreadyExists error")
        assertEquals(
            testUser1.username,
            (error as UserError.UsernameAlreadyExists).username,
            "Expected matching username in error"
        )
    }

    @Test
    fun `insertUser should return EmailAlreadyExists when email is taken`() = runTest {
        // Insert first user
        val firstResult =
            userDao.insertUser(testUser1.username, testUser1.passwordHash, testUser1.email, UserStatus.ACTIVE)
        assertTrue(firstResult.isRight(), "Failed to insert first user")

        // Try to insert user with same email
        val secondResult = userDao.insertUser("differentuser", "differenthash", testUser1.email, UserStatus.ACTIVE)

        assertTrue(secondResult.isLeft(), "Expected Left result for duplicate email")
        val error = secondResult.leftOrNull()
        assertTrue(error is UserError.EmailAlreadyExists, "Expected EmailAlreadyExists error")
        assertEquals(testUser1.email, (error as UserError.EmailAlreadyExists).email, "Expected matching email in error")
    }

    @Test
    fun `updateUser should update existing user successfully`() = runTest {
        // Insert a test user
        val insertResult =
            userDao.insertUser(testUser1.username, testUser1.passwordHash, testUser1.email, UserStatus.ACTIVE)
        assertTrue(insertResult.isRight(), "Failed to insert test user")
        val originalUser = insertResult.getOrNull()!!

        // Update the user
        val updatedUser = originalUser.copy(
            username = "updatedusername",
            passwordHash = "updatedhash",
            email = "updated@example.com"
        )
        val updateResult = userDao.updateUser(updatedUser)

        assertTrue(updateResult.isRight(), "Expected successful user update")

        // Verify the update
        val fetchResult = userDao.getUserById(originalUser.id)
        assertTrue(fetchResult.isRight(), "Failed to fetch updated user")
        val fetchedUser = fetchResult.getOrNull()!!
        assertEquals("updatedusername", fetchedUser.username, "Expected updated username")
        assertEquals("updatedhash", fetchedUser.passwordHash, "Expected updated password hash")
        assertEquals("updated@example.com", fetchedUser.email, "Expected updated email")
    }

    @Test
    fun `updateUser should return UserNotFound when user does not exist`() = runTest {
        val nonExistentUser = testUser1.copy(id = 999L)
        val result = userDao.updateUser(nonExistentUser)

        assertTrue(result.isLeft(), "Expected Left result for non-existing user")
        val error = result.leftOrNull()
        assertTrue(error is UserError.UserNotFound, "Expected UserNotFound error")
        assertEquals(999L, (error as UserError.UserNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `updateLastLogin should update last login timestamp successfully`() = runTest {
        // Insert a test user
        val insertResult =
            userDao.insertUser(testUser1.username, testUser1.passwordHash, testUser1.email, UserStatus.ACTIVE)
        assertTrue(insertResult.isRight(), "Failed to insert test user")
        val user = insertResult.getOrNull()!!

        // Update last login
        val loginTime = System.currentTimeMillis()
        val updateResult = userDao.updateLastLogin(user.id, loginTime)

        assertTrue(updateResult.isRight(), "Expected successful last login update")

        // Verify the update
        val fetchResult = userDao.getUserById(user.id)
        assertTrue(fetchResult.isRight(), "Failed to fetch updated user")
        val fetchedUser = fetchResult.getOrNull()!!
        assertNotNull(fetchedUser.lastLogin, "Expected non-null lastLogin")
        assertEquals(loginTime, fetchedUser.lastLogin.toEpochMilliseconds(), "Expected matching last login timestamp")
    }

    @Test
    fun `updateLastLogin should return UserNotFound when user does not exist`() = runTest {
        val result = userDao.updateLastLogin(999L, System.currentTimeMillis())

        assertTrue(result.isLeft(), "Expected Left result for non-existing user")
        val error = result.leftOrNull()
        assertTrue(error is UserError.UserNotFound, "Expected UserNotFound error")
        assertEquals(999L, (error as UserError.UserNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `deleteUser should delete existing user successfully`() = runTest {
        // Insert a test user
        val insertResult =
            userDao.insertUser(testUser1.username, testUser1.passwordHash, testUser1.email, UserStatus.ACTIVE)
        assertTrue(insertResult.isRight(), "Failed to insert test user")
        val user = insertResult.getOrNull()!!

        // Delete the user
        val deleteResult = userDao.deleteUser(user.id)

        assertTrue(deleteResult.isRight(), "Expected successful user deletion")

        // Verify the deletion
        val fetchResult = userDao.getUserById(user.id)
        assertTrue(fetchResult.isLeft(), "Expected user to be deleted")
        val error = fetchResult.leftOrNull()
        assertTrue(error is UserError.UserNotFound, "Expected UserNotFound error after deletion")
    }

    @Test
    fun `deleteUser should return UserNotFound when user does not exist`() = runTest {
        val result = userDao.deleteUser(999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing user")
        val error = result.leftOrNull()
        assertTrue(error is UserError.UserNotFound, "Expected UserNotFound error")
        assertEquals(999L, (error as UserError.UserNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `getAllUsersWithDetails should return users with roles and groups aggregated`() = runTest {
        // Prepare roles and role assignments
        val roleAdmin = RoleEntity(1L, "Admin", "Administrator")
        val roleUser = RoleEntity(2L, "StandardUser", "Standard user role")

        val assignment1 = UserRoleAssignmentEntity(
            testUser1.id,
            roleAdmin.id,
            Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS)
        )
        val assignment2 = UserRoleAssignmentEntity(
            testUser2.id,
            roleUser.id,
            Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS)
        )

        // Setup users, roles and assignments
        testDataManager.setup(
            TestDataSet(
                users = listOf(testUser1, testUser2),
                roles = listOf(roleAdmin, roleUser),
                userRoleAssignments = listOf(assignment1, assignment2)
            )
        )

        // Also create a user group and membership for user1
        val userGroup = TestDefaults.userGroup1
        testDataManager.insertUserGroup(userGroup)
        testDataManager.insertUserGroupMembership(testUser1.id, userGroup.id)

        val results: List<UserWithDetails> = userDao.getAllUsersWithDetails()

        // Should contain both users
        assertTrue(results.any { it.username == testUser1.username }, "Expected user1 to be present")
        assertTrue(results.any { it.username == testUser2.username }, "Expected user2 to be present")

        val user1Details = results.find { it.id == testUser1.id }!!
        assertTrue(user1Details.roles.any { it.name == "Admin" }, "Expected Admin role for user1")
        assertTrue(
            user1Details.userGroups.any { it.name == userGroup.name },
            "Expected user1 to be member of the inserted group"
        )

        val user2Details = results.find { it.id == testUser2.id }!!
        assertTrue(user2Details.roles.any { it.name == "StandardUser" }, "Expected StandardUser role for user2")
    }

    @Test
    fun `getUserByIdWithDetails should return user details when user exists and UserNotFound when missing`() = runTest {
        // Prepare role and assignment for a single user
        val roleAdmin = RoleEntity(1L, "Admin", "Administrator")
        val assignment1 = UserRoleAssignmentEntity(
            testUser1.id,
            roleAdmin.id,
            Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS)
        )

        testDataManager.setup(
            TestDataSet(
                users = listOf(testUser1),
                roles = listOf(roleAdmin),
                userRoleAssignments = listOf(assignment1)
            )
        )

        // Add group membership
        val userGroup = TestDefaults.userGroup1
        testDataManager.insertUserGroup(userGroup)
        testDataManager.insertUserGroupMembership(testUser1.id, userGroup.id)

        val result = userDao.getUserByIdWithDetails(testUser1.id)
        assertTrue(result.isRight(), "Expected Right for existing user")
        val details = result.getOrNull()!!
        assertEquals(testUser1.username, details.username)
        assertTrue(details.roles.any { it.name == "Admin" })
        assertTrue(details.userGroups.any { it.name == userGroup.name })

        // Non-existent user
        val notFound = userDao.getUserByIdWithDetails(9999L)
        assertTrue(notFound.isLeft(), "Expected Left for non-existent user")
        val err = notFound.leftOrNull()
        assertTrue(err is UserError.UserNotFound)
    }
}
