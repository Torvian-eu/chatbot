package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.PermissionDao
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.RolePermissionDao
import eu.torvian.chatbot.server.data.dao.error.RolePermissionError
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [RolePermissionDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [RolePermissionDao]:
 * - Assigning permissions to roles
 * - Revoking permissions from roles
 * - Handling error cases (assignment already exists, assignment not found, foreign key violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class RolePermissionDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var rolePermissionDao: RolePermissionDao
    private lateinit var roleDao: RoleDao
    private lateinit var permissionDao: PermissionDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private lateinit var testRole1: RoleEntity
    private lateinit var testRole2: RoleEntity
    private lateinit var testPermission1: PermissionEntity
    private lateinit var testPermission2: PermissionEntity

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        rolePermissionDao = container.get()
        roleDao = container.get()
        permissionDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(setOf(
            Table.ROLES,
            Table.PERMISSIONS,
            Table.ROLE_PERMISSIONS
        ))

        // Create test roles
        val role1Result = roleDao.insertRole("testrole1", "Test Role 1 Description")
        val role2Result = roleDao.insertRole("testrole2", "Test Role 2 Description")
        assertTrue(role1Result.isRight() && role2Result.isRight(), "Failed to create test roles")
        testRole1 = role1Result.getOrNull()!!
        testRole2 = role2Result.getOrNull()!!

        // Create test permissions
        val permission1Result = permissionDao.insertPermission("read", "messages")
        val permission2Result = permissionDao.insertPermission("write", "users")
        assertTrue(permission1Result.isRight() && permission2Result.isRight(), "Failed to create test permissions")
        testPermission1 = permission1Result.getOrNull()!!
        testPermission2 = permission2Result.getOrNull()!!
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `assignPermissionToRole should assign permission to role successfully`() = runTest {
        val result = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission1.id)

        assertTrue(result.isRight(), "Expected successful permission assignment")

        // Verify assignment by checking if role has the permission through PermissionDao
        val permissions = permissionDao.getPermissionsByRoleId(testRole1.id)
        assertEquals(1, permissions.size, "Expected 1 permission for role")
        assertEquals(testPermission1.id, permissions.first().id, "Expected assigned permission in results")
    }

    @Test
    fun `assignPermissionToRole should handle multiple permissions for same role`() = runTest {
        // Assign multiple permissions to same role
        val assign1Result = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission1.id)
        val assign2Result = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission2.id)

        assertTrue(assign1Result.isRight() && assign2Result.isRight(), "Expected successful permission assignments")

        // Verify both assignments
        val permissions = permissionDao.getPermissionsByRoleId(testRole1.id)
        assertEquals(2, permissions.size, "Expected 2 permissions for role")
        val permissionIds = permissions.map { it.id }
        assertTrue(permissionIds.contains(testPermission1.id), "Expected testPermission1 in results")
        assertTrue(permissionIds.contains(testPermission2.id), "Expected testPermission2 in results")
    }

    @Test
    fun `assignPermissionToRole should handle same permission for multiple roles`() = runTest {
        // Assign same permission to multiple roles
        val assign1Result = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission1.id)
        val assign2Result = rolePermissionDao.assignPermissionToRole(testRole2.id, testPermission1.id)

        assertTrue(assign1Result.isRight() && assign2Result.isRight(), "Expected successful permission assignments")

        // Verify assignments for both roles
        val permissions1 = permissionDao.getPermissionsByRoleId(testRole1.id)
        val permissions2 = permissionDao.getPermissionsByRoleId(testRole2.id)

        assertEquals(1, permissions1.size, "Expected 1 permission for role1")
        assertEquals(1, permissions2.size, "Expected 1 permission for role2")
        assertEquals(testPermission1.id, permissions1.first().id, "Expected testPermission1 for role1")
        assertEquals(testPermission1.id, permissions2.first().id, "Expected testPermission1 for role2")
    }

    @Test
    fun `assignPermissionToRole should return AssignmentAlreadyExists when permission already assigned`() = runTest {
        // Assign permission first time
        val firstResult = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission1.id)
        assertTrue(firstResult.isRight(), "Failed to assign permission first time")

        // Try to assign same permission again
        val result = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission1.id)

        assertTrue(result.isLeft(), "Expected Left result for duplicate assignment")
        val error = result.leftOrNull()
        assertIs<RolePermissionError.AssignmentAlreadyExists>(error, "Expected AssignmentAlreadyExists error")
        assertEquals(testRole1.id, error.roleId, "Expected matching role ID in error")
        assertEquals(testPermission1.id, error.permissionId, "Expected matching permission ID in error")
    }

    @Test
    fun `assignPermissionToRole should return ForeignKeyViolation when role does not exist`() = runTest {
        val result = rolePermissionDao.assignPermissionToRole(999L, testPermission1.id)

        assertTrue(result.isLeft(), "Expected Left result for non-existing role")
        val error = result.leftOrNull()
        assertIs<RolePermissionError.ForeignKeyViolation>(error, "Expected ForeignKeyViolation error")
    }

    @Test
    fun `assignPermissionToRole should return ForeignKeyViolation when permission does not exist`() = runTest {
        val result = rolePermissionDao.assignPermissionToRole(testRole1.id, 999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing permission")
        val error = result.leftOrNull()
        assertIs<RolePermissionError.ForeignKeyViolation>(error, "Expected ForeignKeyViolation error")
    }

    @Test
    fun `revokePermissionFromRole should revoke permission from role successfully`() = runTest {
        // Assign permission first
        val assignResult = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission1.id)
        assertTrue(assignResult.isRight(), "Failed to assign permission")

        // Revoke the permission
        val revokeResult = rolePermissionDao.revokePermissionFromRole(testRole1.id, testPermission1.id)

        assertTrue(revokeResult.isRight(), "Expected successful permission revocation")

        // Verify revocation
        val permissions = permissionDao.getPermissionsByRoleId(testRole1.id)
        assertTrue(permissions.isEmpty(), "Expected no permissions for role after revocation")
    }

    @Test
    fun `revokePermissionFromRole should only revoke specific permission assignment`() = runTest {
        // Assign multiple permissions to role
        val assign1Result = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission1.id)
        val assign2Result = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission2.id)
        assertTrue(assign1Result.isRight() && assign2Result.isRight(), "Failed to assign permissions")

        // Revoke only one permission
        val revokeResult = rolePermissionDao.revokePermissionFromRole(testRole1.id, testPermission1.id)
        assertTrue(revokeResult.isRight(), "Failed to revoke permission")

        // Verify only the specified permission was revoked
        val permissions = permissionDao.getPermissionsByRoleId(testRole1.id)
        assertEquals(1, permissions.size, "Expected 1 remaining permission for role")
        assertEquals(testPermission2.id, permissions.first().id, "Expected testPermission2 to remain")
    }

    @Test
    fun `revokePermissionFromRole should only affect specific role`() = runTest {
        // Assign same permission to multiple roles
        val assign1Result = rolePermissionDao.assignPermissionToRole(testRole1.id, testPermission1.id)
        val assign2Result = rolePermissionDao.assignPermissionToRole(testRole2.id, testPermission1.id)
        assertTrue(assign1Result.isRight() && assign2Result.isRight(), "Failed to assign permissions")

        // Revoke permission from only one role
        val revokeResult = rolePermissionDao.revokePermissionFromRole(testRole1.id, testPermission1.id)
        assertTrue(revokeResult.isRight(), "Failed to revoke permission")

        // Verify only the specified role-permission assignment was revoked
        val permissions1 = permissionDao.getPermissionsByRoleId(testRole1.id)
        val permissions2 = permissionDao.getPermissionsByRoleId(testRole2.id)

        assertTrue(permissions1.isEmpty(), "Expected no permissions for role1 after revocation")
        assertEquals(1, permissions2.size, "Expected 1 permission remaining for role2")
        assertEquals(testPermission1.id, permissions2.first().id, "Expected testPermission1 to remain for role2")
    }

    @Test
    fun `revokePermissionFromRole should return AssignmentNotFound when assignment does not exist`() = runTest {
        val result = rolePermissionDao.revokePermissionFromRole(testRole1.id, testPermission1.id)

        assertTrue(result.isLeft(), "Expected Left result for non-existing assignment")
        val error = result.leftOrNull()
        assertIs<RolePermissionError.AssignmentNotFound>(error, "Expected AssignmentNotFound error")
        assertEquals(testRole1.id, error.roleId, "Expected matching role ID in error")
        assertEquals(testPermission1.id, error.permissionId, "Expected matching permission ID in error")
    }

    @Test
    fun `revokePermissionFromRole should return AssignmentNotFound when role does not exist`() = runTest {
        val result = rolePermissionDao.revokePermissionFromRole(999L, testPermission1.id)

        assertTrue(result.isLeft(), "Expected Left result for non-existing role")
        val error = result.leftOrNull()
        assertIs<RolePermissionError.AssignmentNotFound>(error, "Expected AssignmentNotFound error")
    }

    @Test
    fun `revokePermissionFromRole should return AssignmentNotFound when permission does not exist`() = runTest {
        val result = rolePermissionDao.revokePermissionFromRole(testRole1.id, 999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing permission")
        val error = result.leftOrNull()
        assertIs<RolePermissionError.AssignmentNotFound>(error, "Expected AssignmentNotFound error")
    }
}
