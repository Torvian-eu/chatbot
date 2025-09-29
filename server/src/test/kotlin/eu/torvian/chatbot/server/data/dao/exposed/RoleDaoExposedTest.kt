package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.error.RoleError
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [RoleDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [RoleDao]:
 * - Getting all roles
 * - Getting a role by ID
 * - Getting a role by name
 * - Inserting a new role
 * - Updating an existing role
 * - Deleting a role
 * - Handling error cases (role not found, role name already exists)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class RoleDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var roleDao: RoleDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testRoleName = "testrole"
    private val testRoleDescription = "Test Role Description"
    private val testRoleName2 = "testrole2"
    private val testRoleDescription2 = "Test Role 2 Description"

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        roleDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(setOf(Table.ROLES))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getAllRoles should return empty list when no roles exist`() = runTest {
        val roles = roleDao.getAllRoles()
        assertTrue(roles.isEmpty(), "Expected empty list when no roles exist")
    }

    @Test
    fun `getAllRoles should return all roles when they exist`() = runTest {
        // Insert test roles
        val role1Result = roleDao.insertRole(testRoleName, testRoleDescription)
        val role2Result = roleDao.insertRole(testRoleName2, testRoleDescription2)
        assertTrue(role1Result.isRight() && role2Result.isRight(), "Failed to insert test roles")

        // Get all roles
        val roles = roleDao.getAllRoles()

        // Verify
        assertEquals(2, roles.size, "Expected 2 roles")
        val roleNames = roles.map { it.name }
        assertTrue(roleNames.contains(testRoleName), "Expected test role name in results")
        assertTrue(roleNames.contains(testRoleName2), "Expected test role name2 in results")
    }

    @Test
    fun `getRoleById should return role when it exists`() = runTest {
        // Insert a test role
        val insertResult = roleDao.insertRole(testRoleName, testRoleDescription)
        assertTrue(insertResult.isRight(), "Failed to insert test role")
        val insertedRole = insertResult.getOrNull()!!

        // Get the role by ID
        val result = roleDao.getRoleById(insertedRole.id)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing role")
        val role = result.getOrNull()
        assertNotNull(role, "Expected non-null role")
        assertEquals(insertedRole.id, role.id, "Expected matching ID")
        assertEquals(testRoleName, role.name, "Expected matching name")
        assertEquals(testRoleDescription, role.description, "Expected matching description")
    }

    @Test
    fun `getRoleById should return RoleNotFound when role does not exist`() = runTest {
        val result = roleDao.getRoleById(999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing role")
        val error = result.leftOrNull()
        assertTrue(error is RoleError.RoleNotFound, "Expected RoleNotFound error")
        assertEquals(999L, (error as RoleError.RoleNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `getRoleByName should return role when it exists`() = runTest {
        // Insert a test role
        val insertResult = roleDao.insertRole(testRoleName, testRoleDescription)
        assertTrue(insertResult.isRight(), "Failed to insert test role")
        val insertedRole = insertResult.getOrNull()!!

        // Get the role by name
        val result = roleDao.getRoleByName(testRoleName)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing role")
        val role = result.getOrNull()
        assertNotNull(role, "Expected non-null role")
        assertEquals(insertedRole.id, role.id, "Expected matching ID")
        assertEquals(testRoleName, role.name, "Expected matching name")
        assertEquals(testRoleDescription, role.description, "Expected matching description")
    }

    @Test
    fun `getRoleByName should return RoleNotFoundByName when role does not exist`() = runTest {
        val result = roleDao.getRoleByName("nonexistent")

        assertTrue(result.isLeft(), "Expected Left result for non-existing role")
        val error = result.leftOrNull()
        assertTrue(error is RoleError.RoleNotFoundByName, "Expected RoleNotFoundByName error")
        assertEquals("nonexistent", (error as RoleError.RoleNotFoundByName).name, "Expected matching name in error")
    }

    @Test
    fun `insertRole should create new role successfully with description`() = runTest {
        val result = roleDao.insertRole(testRoleName, testRoleDescription)

        assertTrue(result.isRight(), "Expected successful role creation")
        val role = result.getOrNull()
        assertNotNull(role, "Expected non-null role")
        assertEquals(testRoleName, role.name, "Expected matching name")
        assertEquals(testRoleDescription, role.description, "Expected matching description")
        assertTrue(role.id > 0, "Expected positive ID")
    }

    @Test
    fun `insertRole should create new role successfully without description`() = runTest {
        val result = roleDao.insertRole(testRoleName, null)

        assertTrue(result.isRight(), "Expected successful role creation")
        val role = result.getOrNull()
        assertNotNull(role, "Expected non-null role")
        assertEquals(testRoleName, role.name, "Expected matching name")
        assertNull(role.description, "Expected null description")
        assertTrue(role.id > 0, "Expected positive ID")
    }

    @Test
    fun `insertRole should return RoleNameAlreadyExists when role with same name exists`() = runTest {
        // Insert the first role
        val firstResult = roleDao.insertRole(testRoleName, testRoleDescription)
        assertTrue(firstResult.isRight(), "Failed to insert first role")

        // Try to insert a role with duplicate name
        val result = roleDao.insertRole(testRoleName, "Different description")

        assertTrue(result.isLeft(), "Expected Left result for duplicate role name")
        val error = result.leftOrNull()
        assertTrue(error is RoleError.RoleNameAlreadyExists, "Expected RoleNameAlreadyExists error")
        assertEquals(testRoleName, (error as RoleError.RoleNameAlreadyExists).name, "Expected matching name in error")
    }

    @Test
    fun `updateRole should update existing role successfully`() = runTest {
        // Insert a test role
        val insertResult = roleDao.insertRole(testRoleName, testRoleDescription)
        assertTrue(insertResult.isRight(), "Failed to insert test role")
        val originalRole = insertResult.getOrNull()!!

        // Update the role
        val updatedRole = originalRole.copy(
            name = "updatedname",
            description = "Updated description"
        )
        val updateResult = roleDao.updateRole(updatedRole)

        assertTrue(updateResult.isRight(), "Expected successful role update")

        // Verify the update
        val fetchResult = roleDao.getRoleById(originalRole.id)
        assertTrue(fetchResult.isRight(), "Failed to fetch updated role")
        val fetchedRole = fetchResult.getOrNull()!!
        assertEquals("updatedname", fetchedRole.name, "Expected updated name")
        assertEquals("Updated description", fetchedRole.description, "Expected updated description")
        assertEquals(originalRole.id, fetchedRole.id, "Expected same ID")
    }

    @Test
    fun `updateRole should return RoleNotFound when role does not exist`() = runTest {
        val nonExistentRole = RoleEntity(
            id = 999L,
            name = "nonexistent",
            description = "Description"
        )

        val result = roleDao.updateRole(nonExistentRole)

        assertTrue(result.isLeft(), "Expected Left result for non-existing role")
        val error = result.leftOrNull()
        assertTrue(error is RoleError.RoleNotFound, "Expected RoleNotFound error")
        assertEquals(999L, (error as RoleError.RoleNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `updateRole should return RoleNameAlreadyExists when updated name conflicts`() = runTest {
        // Insert two test roles
        val role1Result = roleDao.insertRole(testRoleName, testRoleDescription)
        val role2Result = roleDao.insertRole(testRoleName2, testRoleDescription2)
        assertTrue(role1Result.isRight() && role2Result.isRight(), "Failed to insert test roles")
        val role2 = role2Result.getOrNull()!!

        // Try to update role2 to have the same name as role1
        val updatedRole2 = role2.copy(name = testRoleName)
        val result = roleDao.updateRole(updatedRole2)

        assertTrue(result.isLeft(), "Expected Left result for duplicate role name")
        val error = result.leftOrNull()
        assertTrue(error is RoleError.RoleNameAlreadyExists, "Expected RoleNameAlreadyExists error")
        assertEquals(testRoleName, (error as RoleError.RoleNameAlreadyExists).name, "Expected matching name in error")
    }

    @Test
    fun `deleteRole should delete existing role successfully`() = runTest {
        // Insert a test role
        val insertResult = roleDao.insertRole(testRoleName, testRoleDescription)
        assertTrue(insertResult.isRight(), "Failed to insert test role")
        val role = insertResult.getOrNull()!!

        // Delete the role
        val deleteResult = roleDao.deleteRole(role.id)

        assertTrue(deleteResult.isRight(), "Expected successful role deletion")

        // Verify the deletion
        val fetchResult = roleDao.getRoleById(role.id)
        assertTrue(fetchResult.isLeft(), "Expected role to be deleted")
        val error = fetchResult.leftOrNull()
        assertTrue(error is RoleError.RoleNotFound, "Expected RoleNotFound error after deletion")
    }

    @Test
    fun `deleteRole should return RoleNotFound when role does not exist`() = runTest {
        val result = roleDao.deleteRole(999L)

        assertTrue(result.isLeft(), "Expected Left result for non-existing role")
        val error = result.leftOrNull()
        assertTrue(error is RoleError.RoleNotFound, "Expected RoleNotFound error")
        assertEquals(999L, (error as RoleError.RoleNotFound).id, "Expected matching ID in error")
    }

    @Test
    fun `role operations should handle null descriptions correctly`() = runTest {
        // Insert role with null description
        val insertResult = roleDao.insertRole(testRoleName, null)
        assertTrue(insertResult.isRight(), "Failed to insert role with null description")
        val role = insertResult.getOrNull()!!
        assertNull(role.description, "Expected null description")

        // Update role to have a description
        val updatedRole = role.copy(description = testRoleDescription)
        val updateResult = roleDao.updateRole(updatedRole)
        assertTrue(updateResult.isRight(), "Failed to update role description")

        // Verify the update
        val fetchResult = roleDao.getRoleById(role.id)
        assertTrue(fetchResult.isRight(), "Failed to fetch updated role")
        val fetchedRole = fetchResult.getOrNull()!!
        assertEquals(testRoleDescription, fetchedRole.description, "Expected updated description")

        // Update role back to null description
        val nullDescRole = fetchedRole.copy(description = null)
        val nullUpdateResult = roleDao.updateRole(nullDescRole)
        assertTrue(nullUpdateResult.isRight(), "Failed to update role to null description")

        // Verify the null update
        val finalFetchResult = roleDao.getRoleById(role.id)
        assertTrue(finalFetchResult.isRight(), "Failed to fetch role after null update")
        val finalRole = finalFetchResult.getOrNull()!!
        assertNull(finalRole.description, "Expected null description after update")
    }
}
