package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.PermissionDao
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.RolePermissionDao
import eu.torvian.chatbot.server.data.dao.error.PermissionError
import eu.torvian.chatbot.server.data.entities.PermissionEntity
import eu.torvian.chatbot.server.data.entities.RoleEntity
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
 * Tests for [PermissionDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [PermissionDao]:
 * - Getting all permissions
 * - Getting a permission by ID
 * - Getting a permission by action and subject
 * - Inserting a new permission
 * - Deleting a permission
 * - Getting permissions by role ID
 * - Handling error cases (permission not found, permission already exists)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class PermissionDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var permissionDao: PermissionDao
    private lateinit var roleDao: RoleDao
    private lateinit var rolePermissionDao: RolePermissionDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private lateinit var testRole: RoleEntity
    private val testAction = "read"
    private val testSubject = "messages"
    private val testAction2 = "write"
    private val testSubject2 = "users"

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        permissionDao = container.get()
        roleDao = container.get()
        rolePermissionDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(setOf(Table.PERMISSIONS, Table.ROLES, Table.ROLE_PERMISSIONS))

        // Create a test role for role-permission tests
        val roleResult = roleDao.insertRole("testrole", "Test Role Description")
        assertTrue(roleResult.isRight(), "Failed to create test role")
        testRole = roleResult.getOrNull()!!
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getAllPermissions should return empty list when no permissions exist`() = runTest {
        val permissions = permissionDao.getAllPermissions()
        assertTrue(permissions.isEmpty(), "Expected empty list when no permissions exist")
    }

    @Test
    fun `getAllPermissions should return all permissions when they exist`() = runTest {
        // Insert test permissions
        val permission1Result = permissionDao.insertPermission(testAction, testSubject)
        val permission2Result = permissionDao.insertPermission(testAction2, testSubject2)
        assertTrue(permission1Result.isRight() && permission2Result.isRight(), "Failed to insert test permissions")

        // Get all permissions
        val permissions = permissionDao.getAllPermissions()

        // Verify
        assertEquals(2, permissions.size, "Expected 2 permissions")
        val actions = permissions.map { it.action }
        val subjects = permissions.map { it.subject }
        assertTrue(actions.contains(testAction), "Expected test action in results")
        assertTrue(actions.contains(testAction2), "Expected test action2 in results")
        assertTrue(subjects.contains(testSubject), "Expected test subject in results")
        assertTrue(subjects.contains(testSubject2), "Expected test subject2 in results")
    }

    @Test
    fun `getPermissionById should return permission when it exists`() = runTest {
        // Insert a test permission
        val insertResult = permissionDao.insertPermission(testAction, testSubject)
        assertTrue(insertResult.isRight(), "Failed to insert test permission")
        val insertedPermission = insertResult.getOrNull()!!

        // Get the permission by ID
        val result = permissionDao.getPermissionById(insertedPermission.id)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing permission")
        val permission = result.getOrNull()
        assertNotNull(permission, "Expected non-null permission")
        assertEquals(insertedPermission.id, permission.id, "Expected matching ID")
        assertEquals(testAction, permission.action, "Expected matching action")
        assertEquals(testSubject, permission.subject, "Expected matching subject")
    }

    @Test
    fun `getPermissionById should return PermissionNotFound when permission does not exist`() = runTest {
        val result = permissionDao.getPermissionById(999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing permission")
        val error = result.leftOrNull()
        assertTrue(error is PermissionError.PermissionNotFound, "Expected PermissionNotFound error")
        assertEquals(999L, (error as PermissionError.PermissionNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `getPermissionByActionAndSubject should return permission when it exists`() = runTest {
        // Insert a test permission
        val insertResult = permissionDao.insertPermission(testAction, testSubject)
        assertTrue(insertResult.isRight(), "Failed to insert test permission")
        val insertedPermission = insertResult.getOrNull()!!

        // Get the permission by action and subject
        val result = permissionDao.getPermissionByActionAndSubject(testAction, testSubject)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing permission")
        val permission = result.getOrNull()
        assertNotNull(permission, "Expected non-null permission")
        assertEquals(insertedPermission.id, permission.id, "Expected matching ID")
        assertEquals(testAction, permission.action, "Expected matching action")
        assertEquals(testSubject, permission.subject, "Expected matching subject")
    }

    @Test
    fun `getPermissionByActionAndSubject should return PermissionNotFound when permission does not exist`() = runTest {
        val result = permissionDao.getPermissionByActionAndSubject("nonexistent", "action")

        assertTrue(result.isLeft(), "Expected Left result for non-existing permission")
        val error = result.leftOrNull()
        assertTrue(error is PermissionError.PermissionNotFound, "Expected PermissionNotFound error")
    }

    @Test
    fun `insertPermission should create new permission successfully`() = runTest {
        val result = permissionDao.insertPermission(testAction, testSubject)

        assertTrue(result.isRight(), "Expected successful permission creation")
        val permission = result.getOrNull()
        assertNotNull(permission, "Expected non-null permission")
        assertEquals(testAction, permission.action, "Expected matching action")
        assertEquals(testSubject, permission.subject, "Expected matching subject")
        assertTrue(permission.id > 0, "Expected positive ID")
    }

    @Test
    fun `insertPermission should return PermissionAlreadyExists when permission with same action and subject exists`() = runTest {
        // Insert the first permission
        val firstResult = permissionDao.insertPermission(testAction, testSubject)
        assertTrue(firstResult.isRight(), "Failed to insert first permission")

        // Try to insert a duplicate permission
        val result = permissionDao.insertPermission(testAction, testSubject)

        assertTrue(result.isLeft(), "Expected Left result for duplicate permission")
        val error = result.leftOrNull()
        assertTrue(error is PermissionError.PermissionAlreadyExists, "Expected PermissionAlreadyExists error")
        val duplicateError = error as PermissionError.PermissionAlreadyExists
        assertEquals(testAction, duplicateError.action, "Expected matching action in error")
        assertEquals(testSubject, duplicateError.subject, "Expected matching subject in error")
    }

    @Test
    fun `deletePermission should delete existing permission successfully`() = runTest {
        // Insert a test permission
        val insertResult = permissionDao.insertPermission(testAction, testSubject)
        assertTrue(insertResult.isRight(), "Failed to insert test permission")
        val permission = insertResult.getOrNull()!!

        // Delete the permission
        val deleteResult = permissionDao.deletePermission(permission.id)

        assertTrue(deleteResult.isRight(), "Expected successful permission deletion")

        // Verify the deletion
        val fetchResult = permissionDao.getPermissionById(permission.id)
        assertTrue(fetchResult.isLeft(), "Expected permission to be deleted")
        val error = fetchResult.leftOrNull()
        assertTrue(error is PermissionError.PermissionNotFound, "Expected PermissionNotFound error after deletion")
    }

    @Test
    fun `deletePermission should return PermissionNotFound when permission does not exist`() = runTest {
        val result = permissionDao.deletePermission(999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing permission")
        val error = result.leftOrNull()
        assertTrue(error is PermissionError.PermissionNotFound, "Expected PermissionNotFound error")
        assertEquals(999L, (error as PermissionError.PermissionNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `getPermissionsByRoleId should return empty list when role has no permissions`() = runTest {
        val permissions = permissionDao.getPermissionsByRoleId(testRole.id)
        assertTrue(permissions.isEmpty(), "Expected empty list when role has no permissions")
    }

    @Test
    fun `getPermissionsByRoleId should return all permissions for role when they exist`() = runTest {
        // Insert test permissions
        val permission1Result = permissionDao.insertPermission(testAction, testSubject)
        val permission2Result = permissionDao.insertPermission(testAction2, testSubject2)
        assertTrue(permission1Result.isRight() && permission2Result.isRight(), "Failed to insert test permissions")
        val permission1 = permission1Result.getOrNull()!!
        val permission2 = permission2Result.getOrNull()!!

        // Assign permissions to role
        val assign1Result = rolePermissionDao.assignPermissionToRole(testRole.id, permission1.id)
        val assign2Result = rolePermissionDao.assignPermissionToRole(testRole.id, permission2.id)
        assertTrue(assign1Result.isRight() && assign2Result.isRight(), "Failed to assign permissions to role")

        // Get permissions by role ID
        val permissions = permissionDao.getPermissionsByRoleId(testRole.id)

        // Verify
        assertEquals(2, permissions.size, "Expected 2 permissions for role")
        val permissionIds = permissions.map { it.id }
        assertTrue(permissionIds.contains(permission1.id), "Expected permission1 in results")
        assertTrue(permissionIds.contains(permission2.id), "Expected permission2 in results")

        // Verify ordering (should be ordered by action ASC, subject ASC)
        val sortedPermissions = permissions.sortedWith(compareBy<PermissionEntity> { it.action }.thenBy { it.subject })
        assertEquals(sortedPermissions, permissions, "Expected permissions to be sorted by action and subject")
    }

    @Test
    fun `getPermissionsByRoleId should return empty list when role does not exist`() = runTest {
        val permissions = permissionDao.getPermissionsByRoleId(999L)
        assertTrue(permissions.isEmpty(), "Expected empty list when role does not exist")
    }
}
