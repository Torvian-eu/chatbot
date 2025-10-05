package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.dao.error.UserRoleAssignmentError
import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [UserRoleAssignmentDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [UserRoleAssignmentDao]:
 * - Getting roles by user ID
 * - Getting user IDs by role ID
 * - Assigning roles to users
 * - Revoking roles from users
 * - Checking if a user has a specific role
 * - Handling error cases (assignment already exists, assignment not found, foreign key violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class UserRoleAssignmentDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var userRoleAssignmentDao: UserRoleAssignmentDao
    private lateinit var userDao: UserDao
    private lateinit var roleDao: RoleDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private lateinit var testUser1: UserEntity
    private lateinit var testUser2: UserEntity
    private lateinit var testRole1: RoleEntity
    private lateinit var testRole2: RoleEntity

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        userRoleAssignmentDao = container.get()
        userDao = container.get()
        roleDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(setOf(
            Table.USERS,
            Table.ROLES,
            Table.USER_ROLE_ASSIGNMENTS
        ))

        // Create test users
        val user1Result = userDao.insertUser(
            username = "testuser1",
            email = "test1@example.com",
            passwordHash = "hash1",
            status = UserStatus.ACTIVE
        )
        val user2Result = userDao.insertUser(
            username = "testuser2",
            email = "test2@example.com",
            passwordHash = "hash2",
            status = UserStatus.ACTIVE
        )
        assertTrue(user1Result.isRight() && user2Result.isRight(), "Failed to create test users")
        testUser1 = user1Result.getOrNull()!!
        testUser2 = user2Result.getOrNull()!!

        // Create test roles
        val role1Result = roleDao.insertRole("testrole1", "Test Role 1 Description")
        val role2Result = roleDao.insertRole("testrole2", "Test Role 2 Description")
        assertTrue(role1Result.isRight() && role2Result.isRight(), "Failed to create test roles")
        testRole1 = role1Result.getOrNull()!!
        testRole2 = role2Result.getOrNull()!!
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getRolesByUserId should return empty list when user has no roles`() = runTest {
        val roles = userRoleAssignmentDao.getRolesByUserId(testUser1.id)
        assertTrue(roles.isEmpty(), "Expected empty list when user has no roles")
    }

    @Test
    fun `getRolesByUserId should return all roles for user when they exist`() = runTest {
        // Assign roles to user
        val assign1Result = userRoleAssignmentDao.assignRoleToUser(testUser1.id, testRole1.id)
        val assign2Result = userRoleAssignmentDao.assignRoleToUser(testUser1.id, testRole2.id)
        assertTrue(assign1Result.isRight() && assign2Result.isRight(), "Failed to assign roles to user")

        // Get roles by user ID
        val roles = userRoleAssignmentDao.getRolesByUserId(testUser1.id)

        // Verify
        assertEquals(2, roles.size, "Expected 2 roles for user")
        val roleIds = roles.map { it.id }
        assertTrue(roleIds.contains(testRole1.id), "Expected testRole1 in results")
        assertTrue(roleIds.contains(testRole2.id), "Expected testRole2 in results")

        // Verify ordering (should be ordered by role name ASC)
        val sortedRoles = roles.sortedBy { it.name }
        assertEquals(sortedRoles, roles, "Expected roles to be sorted by name")
    }

    @Test
    fun `getRolesByUserId should return empty list when user does not exist`() = runTest {
        val roles = userRoleAssignmentDao.getRolesByUserId(999L)
        assertTrue(roles.isEmpty(), "Expected empty list when user does not exist")
    }

    @Test
    fun `getUserIdsByRoleId should return empty list when role has no users`() = runTest {
        val userIds = userRoleAssignmentDao.getUserIdsByRoleId(testRole1.id)
        assertTrue(userIds.isEmpty(), "Expected empty list when role has no users")
    }

    @Test
    fun `getUserIdsByRoleId should return all user IDs for role when they exist`() = runTest {
        // Assign role to users
        val assign1Result = userRoleAssignmentDao.assignRoleToUser(testUser1.id, testRole1.id)
        val assign2Result = userRoleAssignmentDao.assignRoleToUser(testUser2.id, testRole1.id)
        assertTrue(assign1Result.isRight() && assign2Result.isRight(), "Failed to assign role to users")

        // Get user IDs by role ID
        val userIds = userRoleAssignmentDao.getUserIdsByRoleId(testRole1.id)

        // Verify
        assertEquals(2, userIds.size, "Expected 2 user IDs for role")
        assertTrue(userIds.contains(testUser1.id), "Expected testUser1 ID in results")
        assertTrue(userIds.contains(testUser2.id), "Expected testUser2 ID in results")
    }

    @Test
    fun `getUserIdsByRoleId should return empty list when role does not exist`() = runTest {
        val userIds = userRoleAssignmentDao.getUserIdsByRoleId(999L)
        assertTrue(userIds.isEmpty(), "Expected empty list when role does not exist")
    }

    @Test
    fun `assignRoleToUser should assign role to user successfully`() = runTest {
        val result = userRoleAssignmentDao.assignRoleToUser(testUser1.id, testRole1.id)

        assertTrue(result.isRight(), "Expected successful role assignment")

        // Verify assignment
        val hasRole = userRoleAssignmentDao.hasRole(testUser1.id, testRole1.id)
        assertTrue(hasRole, "Expected user to have the assigned role")
    }

    @Test
    fun `assignRoleToUser should return AssignmentAlreadyExists when role already assigned`() = runTest {
        // Assign role first time
        val firstResult = userRoleAssignmentDao.assignRoleToUser(testUser1.id, testRole1.id)
        assertTrue(firstResult.isRight(), "Failed to assign role first time")

        // Try to assign same role again
        val result = userRoleAssignmentDao.assignRoleToUser(testUser1.id, testRole1.id)

        assertTrue(result.isLeft(), "Expected Left result for duplicate assignment")
        val error = result.leftOrNull()
        assertTrue(error is UserRoleAssignmentError.AssignmentAlreadyExists, "Expected AssignmentAlreadyExists error")
        val duplicateError = error as UserRoleAssignmentError.AssignmentAlreadyExists
        assertEquals(testUser1.id, duplicateError.userId, "Expected matching user ID in error")
        assertEquals(testRole1.id, duplicateError.roleId, "Expected matching role ID in error")
    }

    @Test
    fun `assignRoleToUser should return ForeignKeyViolation when user does not exist`() = runTest {
        val result = userRoleAssignmentDao.assignRoleToUser(999L, testRole1.id)

        assertTrue(result.isLeft(), "Expected Left result for non-existing user")
        val error = result.leftOrNull()
        assertTrue(error is UserRoleAssignmentError.ForeignKeyViolation, "Expected ForeignKeyViolation error")
    }

    @Test
    fun `assignRoleToUser should return ForeignKeyViolation when role does not exist`() = runTest {
        val result = userRoleAssignmentDao.assignRoleToUser(testUser1.id, 999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing role")
        val error = result.leftOrNull()
        assertTrue(error is UserRoleAssignmentError.ForeignKeyViolation, "Expected ForeignKeyViolation error")
    }

    @Test
    fun `revokeRoleFromUser should revoke role from user successfully`() = runTest {
        // Assign role first
        val assignResult = userRoleAssignmentDao.assignRoleToUser(testUser1.id, testRole1.id)
        assertTrue(assignResult.isRight(), "Failed to assign role")

        // Revoke the role
        val revokeResult = userRoleAssignmentDao.revokeRoleFromUser(testUser1.id, testRole1.id)

        assertTrue(revokeResult.isRight(), "Expected successful role revocation")

        // Verify revocation
        val hasRole = userRoleAssignmentDao.hasRole(testUser1.id, testRole1.id)
        assertFalse(hasRole, "Expected user to not have the revoked role")
    }

    @Test
    fun `revokeRoleFromUser should return AssignmentNotFound when assignment does not exist`() = runTest {
        val result = userRoleAssignmentDao.revokeRoleFromUser(testUser1.id, testRole1.id)

        assertTrue(result.isLeft(), "Expected Left result for non-existing assignment")
        val error = result.leftOrNull()
        assertTrue(error is UserRoleAssignmentError.AssignmentNotFound, "Expected AssignmentNotFound error")
        val notFoundError = error as UserRoleAssignmentError.AssignmentNotFound
        assertEquals(testUser1.id, notFoundError.userId, "Expected matching user ID in error")
        assertEquals(testRole1.id, notFoundError.roleId, "Expected matching role ID in error")
    }

    @Test
    fun `hasRole should return true when user has the role`() = runTest {
        // Assign role
        val assignResult = userRoleAssignmentDao.assignRoleToUser(testUser1.id, testRole1.id)
        assertTrue(assignResult.isRight(), "Failed to assign role")

        // Check if user has role
        val hasRole = userRoleAssignmentDao.hasRole(testUser1.id, testRole1.id)

        assertTrue(hasRole, "Expected user to have the assigned role")
    }

    @Test
    fun `hasRole should return false when user does not have the role`() = runTest {
        val hasRole = userRoleAssignmentDao.hasRole(testUser1.id, testRole1.id)

        assertFalse(hasRole, "Expected user to not have the unassigned role")
    }

    @Test
    fun `hasRole should return false when user does not exist`() = runTest {
        val hasRole = userRoleAssignmentDao.hasRole(999L, testRole1.id)

        assertFalse(hasRole, "Expected false when user does not exist")
    }

    @Test
    fun `hasRole should return false when role does not exist`() = runTest {
        val hasRole = userRoleAssignmentDao.hasRole(testUser1.id, 999L)

        assertFalse(hasRole, "Expected false when role does not exist")
    }
}
