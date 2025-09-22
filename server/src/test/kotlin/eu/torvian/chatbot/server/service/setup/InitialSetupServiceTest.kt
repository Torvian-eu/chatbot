package eu.torvian.chatbot.server.service.setup

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserGroupDao
import eu.torvian.chatbot.server.data.tables.*
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Tests for [InitialSetupService].
 *
 * This test suite verifies the initial database setup functionality:
 * - Creating basic roles and permissions
 * - Creating the initial admin user
 * - Assigning roles and virtual group memberships
 * - Handling repeated setup calls
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class InitialSetupServiceTest {
    private lateinit var container: DIContainer
    private lateinit var initialSetupService: InitialSetupService
    private lateinit var userDao: UserDao
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        initialSetupService = container.get()
        userDao = container.get()
        testDataManager = container.get()

        // Create all necessary tables for user management
        testDataManager.createTables(setOf(
            Table.USERS,
            Table.ROLES,
            Table.PERMISSIONS,
            Table.ROLE_PERMISSIONS,
            Table.USER_ROLE_ASSIGNMENTS,
            Table.USER_GROUPS,
            Table.USER_GROUP_MEMBERSHIPS
        ))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `isSetupComplete should return false when no users exist`() = runTest {
        val isComplete = initialSetupService.isSetupComplete()
        assertFalse(isComplete, "Setup should not be complete when no users exist")
    }

    @Test
    fun `isSetupComplete should return true when users exist`() = runTest {
        // Create a user manually
        val userResult = userDao.insertUser("testuser", "hashedpassword", "test@example.com")
        assertTrue(userResult.isRight(), "Failed to create test user")

        val isComplete = initialSetupService.isSetupComplete()
        assertTrue(isComplete, "Setup should be complete when users exist")
    }

    @Test
    fun `performInitialSetup should create admin user successfully`() = runTest {
        val result = initialSetupService.performInitialSetup()
        
        assertTrue(result.isRight(), "Expected successful initial setup")
        val adminUser = result.getOrNull()
        assertNotNull(adminUser, "Expected non-null admin user")
        assertEquals(InitialSetupService.DEFAULT_ADMIN_USERNAME, adminUser.username, "Expected default admin username")
        assertNotNull(adminUser.passwordHash, "Expected non-null password hash")
        assertNotNull(adminUser.createdAt, "Expected non-null createdAt")
        assertNotNull(adminUser.updatedAt, "Expected non-null updatedAt")
    }

    @Test
    fun `performInitialSetup should create basic roles`() = runTest {
        val result = initialSetupService.performInitialSetup()
        assertTrue(result.isRight(), "Expected successful initial setup")

        // Verify roles were created
        val transactionScope = container.get<eu.torvian.chatbot.server.utils.transactions.TransactionScope>()
        val roles = transactionScope.transaction {
            RolesTable.selectAll().toList()
        }
        
        assertTrue(roles.size >= 2, "Expected at least 2 roles to be created")
        val roleNames = roles.map { it[RolesTable.name] }
        assertTrue(roleNames.contains(InitialSetupService.ADMIN_ROLE_NAME), "Expected Admin role to be created")
        assertTrue(roleNames.contains(InitialSetupService.STANDARD_USER_ROLE_NAME), "Expected StandardUser role to be created")
    }

    @Test
    fun `performInitialSetup should create basic permissions`() = runTest {
        val result = initialSetupService.performInitialSetup()
        assertTrue(result.isRight(), "Expected successful initial setup")

        // Verify permissions were created
        val transactionScope = container.get<eu.torvian.chatbot.server.utils.transactions.TransactionScope>()
        val permissions = transactionScope.transaction {
            PermissionsTable.selectAll().toList()
        }
        
        assertTrue(permissions.isNotEmpty(), "Expected permissions to be created")
        val permissionActions = permissions.map { it[PermissionsTable.action] }
        assertTrue(permissionActions.contains("manage"), "Expected 'manage' permission to be created")
        assertTrue(permissionActions.contains("create"), "Expected 'create' permission to be created")
    }

    @Test
    fun `performInitialSetup should assign admin role to admin user`() = runTest {
        val result = initialSetupService.performInitialSetup()
        assertTrue(result.isRight(), "Expected successful initial setup")
        val adminUser = result.getOrNull()!!

        // Verify admin user has admin role
        val transactionScope = container.get<eu.torvian.chatbot.server.utils.transactions.TransactionScope>()
        val userRoleAssignments = transactionScope.transaction {
            UserRoleAssignmentsTable.selectAll()
                .where { UserRoleAssignmentsTable.userId eq adminUser.id }
                .toList()
        }
        
        assertTrue(userRoleAssignments.isNotEmpty(), "Expected admin user to have role assignments")
        
        // Check if admin user has the admin role
        val adminRoleId = transactionScope.transaction {
            RolesTable.selectAll()
                .where { RolesTable.name eq InitialSetupService.ADMIN_ROLE_NAME }
                .single()[RolesTable.id].value
        }
        
        val hasAdminRole = userRoleAssignments.any { 
            it[UserRoleAssignmentsTable.roleId].value == adminRoleId 
        }
        assertTrue(hasAdminRole, "Expected admin user to have admin role")
    }

    @Test
    fun `performInitialSetup should return existing user when setup already completed`() = runTest {
        // Perform initial setup first time
        val firstResult = initialSetupService.performInitialSetup()
        assertTrue(firstResult.isRight(), "Expected successful first setup")
        val firstAdminUser = firstResult.getOrNull()!!

        // Perform initial setup second time
        val secondResult = initialSetupService.performInitialSetup()
        assertTrue(secondResult.isRight(), "Expected successful second setup")
        val secondAdminUser = secondResult.getOrNull()!!

        // Should return the same user (first one created)
        assertEquals(firstAdminUser.id, secondAdminUser.id, "Expected same admin user ID")
        assertEquals(firstAdminUser.username, secondAdminUser.username, "Expected same admin username")
    }

    @Test
    fun `performInitialSetup should not create duplicate data on repeated calls`() = runTest {
        // Perform initial setup twice
        initialSetupService.performInitialSetup()
        initialSetupService.performInitialSetup()

        val transactionScope = container.get<eu.torvian.chatbot.server.utils.transactions.TransactionScope>()
        
        // Verify only one admin user exists
        val users = userDao.getAllUsers()
        assertEquals(1, users.size, "Expected exactly one user after repeated setup")

        // Verify roles are not duplicated
        val adminRoles = transactionScope.transaction {
            RolesTable.selectAll()
                .where { RolesTable.name eq InitialSetupService.ADMIN_ROLE_NAME }
                .count()
        }
        assertEquals(1, adminRoles, "Expected exactly one Admin role after repeated setup")
    }
}
