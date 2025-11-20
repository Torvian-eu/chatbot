package eu.torvian.chatbot.server.service.setup

import eu.torvian.chatbot.common.api.CommonRoles
import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.tables.*
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [UserAccountInitializer].
 *
 * This test suite verifies the user account initialization functionality:
 * - Creating basic roles and permissions
 * - Creating the initial admin user
 * - Creating the "All Users" group
 * - Assigning roles and group memberships
 * - Handling repeated initialization calls
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class UserAccountInitializerTest {
    private lateinit var container: DIContainer
    private lateinit var userAccountInitializer: UserAccountInitializer
    private lateinit var userDao: UserDao
    private lateinit var userGroupService: UserGroupService
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        userAccountInitializer = container.get()
        userDao = container.get()
        userGroupService = container.get()
        testDataManager = container.get()

        // Create all necessary tables for user management
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.ROLES,
                Table.PERMISSIONS,
                Table.ROLE_PERMISSIONS,
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
    fun `isInitialized should return false when no users exist`() = runTest {
        val isInitialized = userAccountInitializer.isInitialized()
        assertFalse(isInitialized, "Should not be initialized when no users exist")
    }

    @Test
    fun `isInitialized should return true when users exist`() = runTest {
        // Create a user manually
        val userResult = userDao.insertUser("testuser", "hashedpassword", "test@example.com", UserStatus.ACTIVE)
        assertTrue(userResult.isRight(), "Failed to create test user")

        val isInitialized = userAccountInitializer.isInitialized()
        assertTrue(isInitialized, "Should be initialized when users exist")
    }

    @Test
    fun `initialize should create admin user successfully`() = runTest {
        val result = userAccountInitializer.initialize()

        assertTrue(result.isRight(), "Expected successful initialization")

        // Verify admin user was created
        val users = userDao.getAllUsers()
        assertEquals(1, users.size, "Expected exactly one user")
        val adminUser = users.first()
        assertEquals(UserAccountInitializer.DEFAULT_ADMIN_USERNAME, adminUser.username, "Expected default admin username")
        assertNotNull(adminUser.passwordHash, "Expected non-null password hash")
        assertNotNull(adminUser.createdAt, "Expected non-null createdAt")
        assertNotNull(adminUser.updatedAt, "Expected non-null updatedAt")
        assertTrue(adminUser.requiresPasswordChange, "Admin should require password change on first login")
    }

    @Test
    fun `initialize should create basic roles`() = runTest {
        val result = userAccountInitializer.initialize()
        assertTrue(result.isRight(), "Expected successful initialization")

        // Verify roles were created
        val transactionScope = container.get<TransactionScope>()
        val roles = transactionScope.transaction {
            RolesTable.selectAll().toList()
        }

        assertTrue(roles.size >= 2, "Expected at least 2 roles to be created")
        val roleNames = roles.map { it[RolesTable.name] }
        assertTrue(roleNames.contains(CommonRoles.ADMIN), "Expected Admin role to be created")
        assertTrue(roleNames.contains(CommonRoles.STANDARD_USER), "Expected StandardUser role to be created")
    }

    @Test
    fun `initialize should create basic permissions`() = runTest {
        val result = userAccountInitializer.initialize()
        assertTrue(result.isRight(), "Expected successful initialization")

        // Verify permissions were created
        val transactionScope = container.get<TransactionScope>()
        val permissions = transactionScope.transaction {
            PermissionsTable.selectAll().toList()
        }

        assertTrue(permissions.isNotEmpty(), "Expected permissions to be created")
        val permissionActions = permissions.map { it[PermissionsTable.action] }
        assertTrue(permissionActions.contains("manage"), "Expected 'manage' permission to be created")
    }

    @Test
    fun `initialize should assign admin role to admin user`() = runTest {
        val result = userAccountInitializer.initialize()
        assertTrue(result.isRight(), "Expected successful initialization")

        val adminUser = userDao.getAllUsers().first()

        // Verify admin user has admin role
        val transactionScope = container.get<TransactionScope>()
        val userRoleAssignments = transactionScope.transaction {
            UserRoleAssignmentsTable.selectAll()
                .where { UserRoleAssignmentsTable.userId eq adminUser.id }
                .toList()
        }

        assertTrue(userRoleAssignments.isNotEmpty(), "Expected admin user to have role assignments")

        // Check if admin user has the admin role
        val adminRoleId = transactionScope.transaction {
            RolesTable.selectAll()
                .where { RolesTable.name eq CommonRoles.ADMIN }
                .single()[RolesTable.id].value
        }

        val hasAdminRole = userRoleAssignments.any {
            it[UserRoleAssignmentsTable.roleId].value == adminRoleId
        }
        assertTrue(hasAdminRole, "Expected admin user to have admin role")
    }

    @Test
    fun `initialize should create All Users group`() = runTest {
        val result = userAccountInitializer.initialize()
        assertTrue(result.isRight(), "Expected successful initialization")

        // Verify "All Users" group was created
        val allUsersGroupResult = userGroupService.getGroupByName(CommonUserGroups.ALL_USERS)
        assertTrue(allUsersGroupResult.isRight(), "Expected All Users group to be created")

        val allUsersGroup = allUsersGroupResult.getOrNull()!!
        assertEquals(CommonUserGroups.ALL_USERS, allUsersGroup.name, "Expected correct group name")
        assertNotNull(allUsersGroup.description, "Expected non-null description")
    }

    @Test
    fun `initialize should add admin user to All Users group`() = runTest {
        val result = userAccountInitializer.initialize()
        assertTrue(result.isRight(), "Expected successful initialization")

        val adminUser = userDao.getAllUsers().first()

        // Get All Users group
        val allUsersGroupResult = userGroupService.getGroupByName(CommonUserGroups.ALL_USERS)
        assertTrue(allUsersGroupResult.isRight(), "Expected All Users group to exist")
        val allUsersGroup = allUsersGroupResult.getOrNull()!!

        // Verify admin user is a member of All Users group
        val transactionScope = container.get<TransactionScope>()
        val memberships = transactionScope.transaction {
            UserGroupMembershipsTable.selectAll()
                .where {
                    (UserGroupMembershipsTable.userId eq adminUser.id) and
                            (UserGroupMembershipsTable.groupId eq allUsersGroup.id)
                }
                .toList()
        }

        assertTrue(memberships.isNotEmpty(), "Expected admin user to be member of All Users group")
    }

    @Test
    fun `initialize should not duplicate All Users group on repeated calls`() = runTest {
        // Initialize twice
        userAccountInitializer.initialize()
        userAccountInitializer.initialize()

        val transactionScope = container.get<TransactionScope>()

        // Verify only one "All Users" group exists
        val allUsersGroupCount = transactionScope.transaction {
            UserGroupsTable.selectAll()
                .where { UserGroupsTable.name eq CommonUserGroups.ALL_USERS }
                .count()
        }
        assertEquals(1, allUsersGroupCount, "Expected exactly one All Users group after repeated initialization")
    }

    @Test
    fun `initialize should be idempotent`() = runTest {
        // Initialize twice
        val firstResult = userAccountInitializer.initialize()
        val secondResult = userAccountInitializer.initialize()

        assertTrue(firstResult.isRight(), "Expected successful first initialization")
        assertTrue(secondResult.isRight(), "Expected successful second initialization")

        val transactionScope = container.get<TransactionScope>()

        // Verify only one admin user exists
        val users = userDao.getAllUsers()
        assertEquals(1, users.size, "Expected exactly one user after repeated initialization")

        // Verify roles are not duplicated
        val adminRoles = transactionScope.transaction {
            RolesTable.selectAll()
                .where { RolesTable.name eq CommonRoles.ADMIN }
                .count()
        }
        assertEquals(1, adminRoles, "Expected exactly one Admin role after repeated initialization")
    }

    @Test
    fun `initialize should skip when already initialized`() = runTest {
        // First initialization
        userAccountInitializer.initialize()
        assertTrue(userAccountInitializer.isInitialized(), "Should be initialized after first run")

        // Get the admin user
        val firstAdminUser = userDao.getAllUsers().first()

        // Second initialization
        userAccountInitializer.initialize()

        // Should still have the same user
        val users = userDao.getAllUsers()
        assertEquals(1, users.size, "Expected exactly one user")
        assertEquals(firstAdminUser.id, users.first().id, "Expected same admin user ID")
        assertEquals(firstAdminUser.username, users.first().username, "Expected same admin username")
    }
}

