package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.server.data.dao.PermissionDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.entities.PermissionEntity
import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.service.security.authorizer.ResourceAuthorizer
import eu.torvian.chatbot.server.service.security.authorizer.ResourceAuthorizerError
import eu.torvian.chatbot.server.service.security.error.AuthorizationError
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
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
    private lateinit var resourceAuthorizer: ResourceAuthorizer
    private lateinit var authorizationService: AuthorizationService

    @BeforeEach
    fun setup() {
        userRoleAssignmentDao = mockk()
        permissionDao = mockk()
        transactionScope = mockk()
        resourceAuthorizer = mockk()

        // Mock transaction scope to execute block directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        authorizationService = AuthorizationServiceImpl(
            mapOf(ResourceType.GROUP to resourceAuthorizer),
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
    fun `hasPermission with PermissionSpec should return true when user has the permission`() = runTest {
        // Given
        val userId = 1L
        val permissionSpec = PermissionSpec("manage", "users")

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")
        val permission = PermissionEntity(1L, "manage", "users")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(adminRole)
        coEvery { permissionDao.getPermissionsByRoleId(adminRole.id) } returns listOf(permission)

        // When
        val result = authorizationService.hasPermission(userId, permissionSpec)

        // Then
        assertTrue(result)
        coVerify { userRoleAssignmentDao.getRolesByUserId(userId) }
        coVerify { permissionDao.getPermissionsByRoleId(adminRole.id) }
    }

    @Test
    fun `hasPermission with PermissionSpec should return false when user lacks permission`() = runTest {
        // Given
        val userId = 1L
        val permissionSpec = PermissionSpec("manage", "users")

        val userRole = RoleEntity(2L, "StandardUser", "Standard user role")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns emptyList()

        // When
        val result = authorizationService.hasPermission(userId, permissionSpec)

        // Then
        assertFalse(result)
    }

    @Test
    fun `hasAnyPermission should return true when user has at least one permission`() = runTest {
        // Given
        val userId = 1L
        val permission1 = PermissionSpec("manage", "users")
        val permission2 = PermissionSpec("create", "sessions")
        val permission3 = PermissionSpec("delete", "providers")

        val userRole = RoleEntity(1L, "StandardUser", "Standard user role")
        val userPermission = PermissionEntity(1L, "create", "sessions")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns listOf(userPermission)

        // When
        val result = authorizationService.hasAnyPermission(userId, permission1, permission2, permission3)

        // Then
        assertTrue(result)
    }

    @Test
    fun `hasAnyPermission should return false when user has none of the permissions`() = runTest {
        // Given
        val userId = 1L
        val permission1 = PermissionSpec("manage", "users")
        val permission2 = PermissionSpec("delete", "providers")

        val userRole = RoleEntity(1L, "StandardUser", "Standard user role")
        val userPermission = PermissionEntity(1L, "create", "sessions")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns listOf(userPermission)

        // When
        val result = authorizationService.hasAnyPermission(userId, permission1, permission2)

        // Then
        assertFalse(result)
    }

    @Test
    fun `hasAnyPermission should return false when no permissions are provided`() = runTest {
        // Given
        val userId = 1L

        // When
        val result = authorizationService.hasAnyPermission(userId)

        // Then
        assertFalse(result)
        coVerify(exactly = 0) { userRoleAssignmentDao.getRolesByUserId(any()) }
    }

    @Test
    fun `hasAnyPermission should return false when user has no roles`() = runTest {
        // Given
        val userId = 1L
        val permission1 = PermissionSpec("manage", "users")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns emptyList()

        // When
        val result = authorizationService.hasAnyPermission(userId, permission1)

        // Then
        assertFalse(result)
    }

    @Test
    fun `hasAllPermissions should return true when user has all permissions`() = runTest {
        // Given
        val userId = 1L
        val permissions = listOf(
            PermissionSpec("manage", "users"),
            PermissionSpec("create", "sessions")
        )

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")
        val rolePermissions = listOf(
            PermissionEntity(1L, "manage", "users"),
            PermissionEntity(2L, "create", "sessions"),
            PermissionEntity(3L, "delete", "providers")
        )

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(adminRole)
        coEvery { permissionDao.getPermissionsByRoleId(adminRole.id) } returns rolePermissions

        // When
        val result = authorizationService.hasAllPermissions(userId, permissions)

        // Then
        assertTrue(result)
    }

    @Test
    fun `hasAllPermissions should return false when user lacks one permission`() = runTest {
        // Given
        val userId = 1L
        val permissions = listOf(
            PermissionSpec("manage", "users"),
            PermissionSpec("create", "sessions"),
            PermissionSpec("delete", "providers")
        )

        val userRole = RoleEntity(1L, "StandardUser", "Standard user role")
        val rolePermissions = listOf(
            PermissionEntity(1L, "manage", "users"),
            PermissionEntity(2L, "create", "sessions")
            // Missing delete:providers
        )

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns rolePermissions

        // When
        val result = authorizationService.hasAllPermissions(userId, permissions)

        // Then
        assertFalse(result)
    }

    @Test
    fun `hasAllPermissions should return false when no permissions are provided`() = runTest {
        // Given
        val userId = 1L

        // When
        val result = authorizationService.hasAllPermissions(userId, emptyList())

        // Then
        assertFalse(result)
    }

    @Test
    fun `hasAllPermissions should return false when user has no roles`() = runTest {
        // Given
        val userId = 1L
        val permissions = listOf(PermissionSpec("manage", "users"))

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns emptyList()

        // When
        val result = authorizationService.hasAllPermissions(userId, permissions)

        // Then
        assertFalse(result)
    }

    @Test
    fun `requirePermission with PermissionSpec should return Unit when user has permission`() = runTest {
        // Given
        val userId = 1L
        val permissionSpec = PermissionSpec("manage", "users")

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")
        val permission = PermissionEntity(1L, "manage", "users")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(adminRole)
        coEvery { permissionDao.getPermissionsByRoleId(adminRole.id) } returns listOf(permission)

        // When
        val result = authorizationService.requirePermission(userId, permissionSpec)

        // Then
        assertTrue(result.isRight())
    }

    @Test
    fun `requirePermission with PermissionSpec should return PermissionDenied when user lacks permission`() = runTest {
        // Given
        val userId = 1L
        val permissionSpec = PermissionSpec("manage", "users")

        val userRole = RoleEntity(2L, "StandardUser", "Standard user role")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns emptyList()

        // When
        val result = authorizationService.requirePermission(userId, permissionSpec)

        // Then
        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is AuthorizationError.PermissionDenied)
            assertEquals(userId, error.userId)
            assertEquals("manage", error.action)
            assertEquals("users", error.subject)
        }
    }

    @Test
    fun `requireAnyPermission should return Unit when user has at least one permission`() = runTest {
        // Given
        val userId = 1L
        val permission1 = PermissionSpec("manage", "users")
        val permission2 = PermissionSpec("create", "sessions")

        val userRole = RoleEntity(1L, "StandardUser", "Standard user role")
        val userPermission = PermissionEntity(1L, "create", "sessions")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns listOf(userPermission)

        // When
        val result = authorizationService.requireAnyPermission(userId, permission1, permission2)

        // Then
        assertTrue(result.isRight())
    }

    @Test
    fun `requireAnyPermission should return AnyPermissionDenied when user has none`() = runTest {
        // Given
        val userId = 1L
        val permission1 = PermissionSpec("manage", "users")
        val permission2 = PermissionSpec("delete", "providers")

        val userRole = RoleEntity(1L, "StandardUser", "Standard user role")

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns emptyList()

        // When
        val result = authorizationService.requireAnyPermission(userId, permission1, permission2)

        // Then
        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is AuthorizationError.AnyPermissionDenied)
            assertEquals(userId, error.userId)
            assertEquals(2, error.permissions.size)
        }
    }

    @Test
    fun `requireAllPermissions should return Unit when user has all permissions`() = runTest {
        // Given
        val userId = 1L
        val permissions = listOf(
            PermissionSpec("manage", "users"),
            PermissionSpec("create", "sessions")
        )

        val adminRole = RoleEntity(1L, "Admin", "Administrator role")
        val rolePermissions = listOf(
            PermissionEntity(1L, "manage", "users"),
            PermissionEntity(2L, "create", "sessions")
        )

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(adminRole)
        coEvery { permissionDao.getPermissionsByRoleId(adminRole.id) } returns rolePermissions

        // When
        val result = authorizationService.requireAllPermissions(userId, permissions)

        // Then
        assertTrue(result.isRight())
    }

    @Test
    fun `requireAllPermissions should return AllPermissionsDenied when user lacks one permission`() = runTest {
        // Given
        val userId = 1L
        val permissions = listOf(
            PermissionSpec("manage", "users"),
            PermissionSpec("delete", "providers")
        )

        val userRole = RoleEntity(1L, "StandardUser", "Standard user role")
        val rolePermissions = listOf(
            PermissionEntity(1L, "manage", "users")
            // Missing delete:providers
        )

        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns listOf(userRole)
        coEvery { permissionDao.getPermissionsByRoleId(userRole.id) } returns rolePermissions

        // When
        val result = authorizationService.requireAllPermissions(userId, permissions)

        // Then
        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is AuthorizationError.AllPermissionsDenied)
            assertEquals(userId, error.userId)
            assertEquals(2, error.permissions.size)
        }
    }

    @Test
    fun `requireAccess should return Unit when authorizer grants access`() = runTest {
        // Given
        val userId = 1L
        val resourceType = ResourceType.GROUP
        val resourceId = 42L
        val accessMode = AccessMode.READ

        coEvery { resourceAuthorizer.requireAccess(userId, resourceId, accessMode) } returns Unit.right()

        // When
        val result = authorizationService.requireAccess(userId, resourceType, resourceId, accessMode)

        // Then
        assertTrue(result.isRight())
        coVerify { resourceAuthorizer.requireAccess(userId, resourceId, accessMode) }
    }

    @Test
    fun `requireAccess should return AccessDenied when authorizer denies access`() = runTest {
        // Given
        val userId = 1L
        val resourceType = ResourceType.GROUP
        val resourceId = 42L
        val accessMode = AccessMode.WRITE

        coEvery { resourceAuthorizer.requireAccess(userId, resourceId, accessMode) } returns
                ResourceAuthorizerError.AccessDenied("Access denied").left()

        // When
        val result = authorizationService.requireAccess(userId, resourceType, resourceId, accessMode)

        // Then
        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is ResourceAuthorizationError.AccessDenied)
            assertEquals(userId, error.userId)
            assertEquals(resourceType, error.resourceType)
            assertEquals(resourceId, error.id)
            assertEquals(accessMode, error.accessMode)
        }
    }

    @Test
    fun `requireAccess should return ResourceNotFound when authorizer reports not found`() = runTest {
        // Given
        val userId = 1L
        val resourceType = ResourceType.GROUP
        val resourceId = 999L
        val accessMode = AccessMode.READ

        coEvery { resourceAuthorizer.requireAccess(userId, resourceId, accessMode) } returns
                ResourceAuthorizerError.ResourceNotFound(resourceId).left()

        // When
        val result = authorizationService.requireAccess(userId, resourceType, resourceId, accessMode)

        // Then
        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is ResourceAuthorizationError.ResourceNotFound)
            assertEquals(resourceType, error.resourceType)
            assertEquals(resourceId, error.id)
        }
    }

    @Test
    fun `requireAccess should return UnsupportedResourceType when no authorizer exists`() = runTest {
        // Given
        val userId = 1L
        val resourceType = ResourceType.SESSION // No authorizer for SESSION
        val resourceId = 42L
        val accessMode = AccessMode.READ

        // When
        val result = authorizationService.requireAccess(userId, resourceType, resourceId, accessMode)

        // Then
        assertTrue(result.isLeft())
        result.onLeft { error ->
            assertTrue(error is ResourceAuthorizationError.UnsupportedResourceType)
            assertEquals(resourceType, error.resourceType)
        }
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
}
