package eu.torvian.chatbot.server.service.security

import eu.torvian.chatbot.server.data.dao.PermissionDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.entities.PermissionEntity
import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.service.security.error.AuthorizationError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthorizationServiceImplTest {
    private lateinit var userRoleAssignmentDao: UserRoleAssignmentDao
    private lateinit var permissionDao: PermissionDao
    private lateinit var transactionScope: TransactionScope
    private lateinit var authorizationService: AuthorizationService

    @BeforeEach
    fun setup() {
        userRoleAssignmentDao = mockk()
        permissionDao = mockk()
        transactionScope = mockk()

        // Mock transaction scope to execute block directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        authorizationService = AuthorizationServiceImpl(
            userRoleAssignmentDao,
            permissionDao,
            transactionScope
        )
    }

    @Test
    fun `hasPermission should return true when user has the permission`() = runTest {
        // Given
        val userId = 1L
        val action = "manage"
        val subject = "users"

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")
        val permission = PermissionEntity(1L, "manage", "users")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(adminRole)
        coEvery { permissionDao.getPermissionsByRoleId(adminRole.id) } returns listOf(permission)

        // When
        val result = authorizationService.hasPermission(userId, action, subject)

        // Then
        assertTrue(result)
        coVerify { userRoleAssignmentDao.getRolesByUserId(userId) }
        coVerify { permissionDao.getPermissionsByRoleId(adminRole.id) }
    }

    @Test
    fun `hasPermission should return false when user does not have the permission`() = runTest {
        // Given
        val userId = 1L
        val action = "manage"
        val subject = "users"

        val userRole = RoleEntity(2L, "StandardUser", "Standard user role")
        val permission = PermissionEntity(2L, "create", "sessions") // Different permission

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns listOf(permission)

        // When
        val result = authorizationService.hasPermission(userId, action, subject)

        // Then
        assertFalse(result)
    }

    @Test
    fun `hasPermission should return false when user has no roles`() = runTest {
        // Given
        val userId = 1L

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns emptyList()

        // When
        val result = authorizationService.hasPermission(userId, "manage", "users")

        // Then
        assertFalse(result)
        coVerify(exactly = 0) { permissionDao.getPermissionsByRoleId(any()) }
    }

    @Test
    fun `hasPermission should aggregate permissions from multiple roles`() = runTest {
        // Given
        val userId = 1L
        val action = "create"
        val subject = "public_provider"

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")
        val moderatorRole = RoleEntity(2L, "Moderator", "Moderator role")

        val adminPermissions = listOf(
            PermissionEntity(1L, "manage", "users")
        )
        val moderatorPermissions = listOf(
            PermissionEntity(2L, "create", "public_provider")
        )

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns
                listOf(adminRole, moderatorRole)
        coEvery { permissionDao.getPermissionsByRoleId(adminRole.id) } returns adminPermissions
        coEvery { permissionDao.getPermissionsByRoleId(moderatorRole.id) } returns moderatorPermissions

        // When
        val result = authorizationService.hasPermission(userId, action, subject)

        // Then
        assertTrue(result)
    }

    @Test
    fun `hasRole should return true when user has the role`() = runTest {
        // Given
        val userId = 1L
        val roleName = "Admin"

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(adminRole)

        // When
        val result = authorizationService.hasRole(userId, roleName)

        // Then
        assertTrue(result)
    }

    @Test
    fun `hasRole should return false when user does not have the role`() = runTest {
        // Given
        val userId = 1L
        val roleName = "Admin"

        val userRole = RoleEntity(2L, "StandardUser", "Standard user role")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)

        // When
        val result = authorizationService.hasRole(userId, roleName)

        // Then
        assertFalse(result)
    }

    @Test
    fun `getUserRoles should return all user roles`() = runTest {
        // Given
        val userId = 1L

        val roles = listOf(
            RoleEntity(1L, "Admin", "Administrator role"),
            RoleEntity(2L, "Moderator", "Moderator role")
        )

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns roles

        // When
        val result = authorizationService.getUserRoles(userId)

        // Then
        assertEquals(2, result.size)
        assertEquals("Admin", result[0].name)
        assertEquals("Moderator", result[1].name)
    }

    @Test
    fun `getUserPermissions should return unique permissions from all roles`() = runTest {
        // Given
        val userId = 1L

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")
        val moderatorRole = RoleEntity(2L, "Moderator", "Moderator role")

        val sharedPermission = PermissionEntity(1L, "manage", "users")
        val adminOnlyPermission = PermissionEntity(2L, "delete", "users")
        val moderatorOnlyPermission = PermissionEntity(3L, "create", "public_provider")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns
                listOf(adminRole, moderatorRole)
        coEvery { permissionDao.getPermissionsByRoleId(adminRole.id) } returns
                listOf(sharedPermission, adminOnlyPermission)
        coEvery { permissionDao.getPermissionsByRoleId(moderatorRole.id) } returns
                listOf(sharedPermission, moderatorOnlyPermission)

        // When
        val result = authorizationService.getUserPermissions(userId)

        // Then
        assertEquals(3, result.size) // Duplicates removed
        assertTrue(result.any { it.action == "manage" && it.subject == "users" })
        assertTrue(result.any { it.action == "delete" && it.subject == "users" })
        assertTrue(result.any { it.action == "create" && it.subject == "public_provider" })
    }

    @Test
    fun `requirePermission should return Unit when user has permission`() = runTest {
        // Given
        val userId = 1L
        val action = "manage"
        val subject = "users"

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")
        val permission = PermissionEntity(1L, "manage", "users")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(adminRole)
        coEvery { permissionDao.getPermissionsByRoleId(adminRole.id) } returns listOf(permission)

        // When
        val result = authorizationService.requirePermission(userId, action, subject)

        // Then
        assertTrue(result.isRight())
    }

    @Test
    fun `requirePermission should return PermissionDenied when user lacks permission`() = runTest {
        // Given
        val userId = 1L
        val action = "manage"
        val subject = "users"

        val userRole = RoleEntity(2L, "StandardUser", "Standard user role")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns emptyList()

        // When
        val result = authorizationService.requirePermission(userId, action, subject)

        // Then
        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is AuthorizationError.PermissionDenied)
            assertEquals(userId, (error as AuthorizationError.PermissionDenied).userId)
            assertEquals(action, error.action)
            assertEquals(subject, error.subject)
        }
    }

    @Test
    fun `requireRole should return Unit when user has role`() = runTest {
        // Given
        val userId = 1L
        val roleName = "Admin"

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(adminRole)

        // When
        val result = authorizationService.requireRole(userId, roleName)

        // Then
        assertTrue(result.isRight())
    }

    @Test
    fun `requireRole should return RoleRequired when user lacks role`() = runTest {
        // Given
        val userId = 1L
        val roleName = "Admin"

        val userRole = RoleEntity(2L, "StandardUser", "Standard user role")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)

        // When
        val result = authorizationService.requireRole(userId, roleName)

        // Then
        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is AuthorizationError.RoleRequired)
            assertEquals(userId, (error as AuthorizationError.RoleRequired).userId)
            assertEquals(roleName, error.roleName)
        }
    }
}
