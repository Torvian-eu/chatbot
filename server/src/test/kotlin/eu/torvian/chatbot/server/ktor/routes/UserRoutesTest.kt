package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.CommonRoles
import eu.torvian.chatbot.common.api.resources.UserResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.UserStatus
import eu.torvian.chatbot.common.models.admin.AssignRoleRequest
import eu.torvian.chatbot.common.models.admin.ChangePasswordRequest
import eu.torvian.chatbot.common.models.admin.UpdateUserRequest
import eu.torvian.chatbot.server.data.entities.*
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for User Management API routes.
 *
 * This test suite verifies the HTTP endpoints for admin user management:
 * - GET /api/v1/users - List all users
 * - GET /api/v1/users/{userId} - Get user by ID
 * - PUT /api/v1/users/{userId} - Update user profile
 * - DELETE /api/v1/users/{userId} - Delete user
 * - GET /api/v1/users/{userId}/roles - Get user's roles
 * - POST /api/v1/users/{userId}/roles - Assign role to user
 * - DELETE /api/v1/users/{userId}/roles/{roleId} - Revoke role from user
 * - PUT /api/v1/users/{userId}/password - Change user password
 */
class UserRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var userTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager
    private lateinit var authHelper: TestAuthHelper

    // Test data
    private val adminUser = UserEntity(
        id = 1L,
        username = "admin",
        email = "admin@example.com",
        passwordHash = "hashed-password",
        status = UserStatus.ACTIVE,
        createdAt = TestDefaults.DEFAULT_INSTANT,
        updatedAt = TestDefaults.DEFAULT_INSTANT,
        lastLogin = null
    )

    private val standardUser = UserEntity(
        id = 2L,
        username = "user",
        email = "user@example.com",
        passwordHash = "hashed-password",
        status = UserStatus.ACTIVE,
        createdAt = TestDefaults.DEFAULT_INSTANT,
        updatedAt = TestDefaults.DEFAULT_INSTANT,
        lastLogin = null
    )

    private val otherUser = UserEntity(
        id = 3L,
        username = "other",
        email = "other@example.com",
        passwordHash = "hashed-password",
        status = UserStatus.ACTIVE,
        createdAt = TestDefaults.DEFAULT_INSTANT,
        updatedAt = TestDefaults.DEFAULT_INSTANT,
        lastLogin = null
    )

    private val adminRole = RoleEntity(
        id = 1L,
        name = CommonRoles.ADMIN,
        description = "Administrator role with full permissions"
    )

    private val standardUserRole = RoleEntity(
        id = 2L,
        name = CommonRoles.STANDARD_USER,
        description = "Standard user role with basic permissions"
    )

    private val manageUsersPermission = PermissionEntity(
        1L,
        CommonPermissions.MANAGE_USERS
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        userTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureUserRoutes(this)
            }
        )

        testDataManager = container.get()
        authHelper = TestAuthHelper(container)

        // Setup required tables
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.ROLES,
                Table.USER_GROUPS,
                Table.PERMISSIONS,
                Table.USER_ROLE_ASSIGNMENTS,
                Table.USER_GROUP_MEMBERSHIPS,
                Table.ROLE_PERMISSIONS,
                Table.USER_SESSIONS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    private suspend fun setupAdminUserWithPermissions() {
        val dataSet = TestDataSet(
            users = listOf(adminUser),
            roles = listOf(adminRole),
            permissions = listOf(manageUsersPermission),
            rolePermissions = listOf(
                RolePermissionEntity(
                    roleId = adminRole.id,
                    permissionId = manageUsersPermission.id
                )
            ),
            userRoleAssignments = listOf(
                UserRoleAssignmentEntity(
                    userId = adminUser.id,
                    roleId = adminRole.id,
                    assignedAt = TestDefaults.DEFAULT_INSTANT
                )
            )
        )
        testDataManager.setup(dataSet)
    }

    private suspend fun setupStandardUser() {
        val dataSet = TestDataSet(
            users = listOf(standardUser),
            roles = listOf(standardUserRole),
            userRoleAssignments = listOf(
                UserRoleAssignmentEntity(
                    userId = standardUser.id,
                    roleId = standardUserRole.id,
                    assignedAt = TestDefaults.DEFAULT_INSTANT
                )
            )
        )
        testDataManager.setup(dataSet)
    }

    // ========== List Users Tests ==========

    @Test
    fun `GET users - admin can list all users`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()
        testDataManager.insertUser(otherUser)

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(href(UserResource())) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val users = response.body<List<User>>()
        assertEquals(3, users.size)
        assertTrue(users.any { it.username == "admin" })
        assertTrue(users.any { it.username == "user" })
        assertTrue(users.any { it.username == "other" })
    }

    @Test
    fun `GET users - non-admin returns 403`() = userTestApplication {
        // Arrange
        setupStandardUser()

        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        // Act
        val response = client.get(href(UserResource())) {
            bearerAuth(userToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
        assertEquals("Permission denied", error.message)
    }

    @Test
    fun `GET users - unauthenticated returns 401`() = userTestApplication {
        // Act
        val response = client.get(href(UserResource()))

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ========== Get User By ID Tests ==========

    @Test
    fun `GET users by id - admin can get user details`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(href(UserResource.ById(userId = standardUser.id))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val user = response.body<User>()
        assertEquals(standardUser.id, user.id)
        assertEquals(standardUser.username, user.username)
        assertEquals(standardUser.email, user.email)
    }

    @Test
    fun `GET users by id - returns 404 for non-existent user`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(href(UserResource.ById(userId = 999L))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("User not found", error.message)
    }

    @Test
    fun `GET users by id - non-admin returns 403`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        // Act
        val response = client.get(href(UserResource.ById(userId = adminUser.id))) {
            bearerAuth(userToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    // ========== Update User Tests ==========

    @Test
    fun `PUT users by id - admin can update user profile`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        val updateRequest = UpdateUserRequest(
            username = "updateduser",
            email = "updated@example.com"
        )

        // Act
        val response = client.put(href(UserResource.ById(userId = standardUser.id))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val updatedUser = response.body<User>()
        assertEquals("updateduser", updatedUser.username)
        assertEquals("updated@example.com", updatedUser.email)
    }

    @Test
    fun `PUT users by id - returns 409 when username already exists`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        val updateRequest = UpdateUserRequest(
            username = "admin", // Already exists
            email = "newemail@example.com"
        )

        // Act
        val response = client.put(href(UserResource.ById(userId = standardUser.id))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.code)
        assertTrue(error.message.contains("Username already exists"))
    }

    @Test
    fun `PUT users by id - returns 400 for invalid input`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        val updateRequest = UpdateUserRequest(
            username = "", // Invalid blank username
            email = "valid@example.com"
        )

        // Act
        val response = client.put(href(UserResource.ById(userId = standardUser.id))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
    }

    @Test
    fun `PUT users by id - non-admin returns 403`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        val updateRequest = UpdateUserRequest(
            username = "newname",
            email = "new@example.com"
        )

        // Act
        val response = client.put(href(UserResource.ById(userId = adminUser.id))) {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(updateRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    // ========== Delete User Tests ==========

    @Test
    fun `DELETE users by id - admin can delete user`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()
        testDataManager.insertUser(otherUser)

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(href(UserResource.ById(userId = otherUser.id))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE users by id - returns 409 when deleting last admin`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(href(UserResource.ById(userId = adminUser.id))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.CONFLICT.code, error.code)
        assertTrue(error.message.contains("Cannot delete the last administrator"))
    }

    @Test
    fun `DELETE users by id - returns 404 for non-existent user`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(href(UserResource.ById(userId = 999L))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals("User not found", error.message)
    }

    @Test
    fun `DELETE users by id - non-admin returns 403`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        // Act
        val response = client.delete(href(UserResource.ById(userId = adminUser.id))) {
            bearerAuth(userToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    // ========== Get User Roles Tests ==========

    @Test
    fun `GET users roles - admin can get user roles`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(href(UserResource.ById.Roles(UserResource.ById(userId = standardUser.id)))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val roles = response.body<List<Role>>()
        assertEquals(1, roles.size)
        assertEquals("StandardUser", roles[0].name)
    }

    @Test
    fun `GET users roles - non-admin returns 403`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        // Act
        val response = client.get(href(UserResource.ById.Roles(UserResource.ById(userId = adminUser.id)))) {
            bearerAuth(userToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ========== Assign Role Tests ==========

    @Test
    fun `POST users roles - admin can assign role to user`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUser(otherUser)
        testDataManager.insertRole(standardUserRole)

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        val assignRequest = AssignRoleRequest(roleId = standardUserRole.id)

        // Act
        val response = client.post(href(UserResource.ById.Roles(UserResource.ById(userId = otherUser.id)))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(assignRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `POST users roles - returns 409 when role already assigned`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        val assignRequest = AssignRoleRequest(roleId = standardUserRole.id) // Already assigned

        // Act
        val response = client.post(href(UserResource.ById.Roles(UserResource.ById(userId = standardUser.id)))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(assignRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.CONFLICT.code, error.code)
        assertTrue(error.message.contains("Role already assigned"))
    }

    @Test
    fun `POST users roles - returns 404 when user or role not found`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        val assignRequest = AssignRoleRequest(roleId = 999L) // Non-existent role

        // Act
        val response = client.post(href(UserResource.ById.Roles(UserResource.ById(userId = standardUser.id)))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(assignRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertTrue(error.message.contains("User or role not found"))
    }

    @Test
    fun `POST users roles - non-admin returns 403`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        val assignRequest = AssignRoleRequest(roleId = adminRole.id)

        // Act
        val response = client.post(href(UserResource.ById.Roles(UserResource.ById(userId = adminUser.id)))) {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(assignRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    // ========== Revoke Role Tests ==========

    @Test
    fun `DELETE users roles by roleId - admin can revoke role from user`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(
            href(
                UserResource.ById.Roles.ByRoleId(
                    UserResource.ById.Roles(UserResource.ById(userId = standardUser.id)),
                    roleId = standardUserRole.id
                )
            )
        ) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE users roles by roleId - returns 409 when revoking admin from last admin`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(
            href(
                UserResource.ById.Roles.ByRoleId(
                    UserResource.ById.Roles(UserResource.ById(userId = adminUser.id)),
                    roleId = adminRole.id
                )
            )
        ) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.CONFLICT.code, error.code)
        assertTrue(error.message.contains("Cannot revoke admin role from the last administrator"))
    }

    @Test
    fun `DELETE users roles by roleId - returns 404 when role not assigned`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(
            href(
                UserResource.ById.Roles.ByRoleId(
                    UserResource.ById.Roles(UserResource.ById(userId = standardUser.id)),
                    roleId = adminRole.id // Not assigned to standardUser
                )
            )
        ) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertTrue(error.message.contains("Role not assigned to user"))
    }

    @Test
    fun `DELETE users roles by roleId - non-admin returns 403`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        // Act
        val response = client.delete(
            href(
                UserResource.ById.Roles.ByRoleId(
                    UserResource.ById.Roles(UserResource.ById(userId = adminUser.id)),
                    roleId = adminRole.id
                )
            )
        ) {
            bearerAuth(userToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    // ========== Change Password Tests ==========

    @Test
    fun `PUT users password - admin can change user password`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        val changePasswordRequest = ChangePasswordRequest(newPassword = authHelper.securePassword)

        // Act
        val response = client.put(href(UserResource.ById.Password(UserResource.ById(userId = standardUser.id)))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(changePasswordRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `PUT users password - returns 400 for invalid password`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        val changePasswordRequest = ChangePasswordRequest(newPassword = "") // Empty password

        // Act
        val response = client.put(href(UserResource.ById.Password(UserResource.ById(userId = standardUser.id)))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(changePasswordRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
    }

    @Test
    fun `PUT users password - returns 404 for non-existent user`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        val changePasswordRequest = ChangePasswordRequest(newPassword = authHelper.securePassword)

        // Act
        val response = client.put(href(UserResource.ById.Password(UserResource.ById(userId = 999L)))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(changePasswordRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
    }

    @Test
    fun `PUT users password - non-admin returns 403`() = userTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        setupStandardUser()

        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        val changePasswordRequest = ChangePasswordRequest(newPassword = authHelper.securePassword)

        // Act
        val response = client.put(href(UserResource.ById.Password(UserResource.ById(userId = adminUser.id)))) {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(changePasswordRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }
}
