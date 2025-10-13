package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.CommonRoles
import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.api.resources.UserGroupResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.admin.AddUserToGroupRequest
import eu.torvian.chatbot.common.models.api.admin.CreateUserGroupRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserGroupRequest
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.common.models.user.UserStatus
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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for User Group Management API routes.
 *
 * This test suite verifies the HTTP endpoints for user group management:
 * - GET /api/v1/user-groups - List all user groups
 * - POST /api/v1/user-groups - Create new user group
 * - GET /api/v1/user-groups/{groupId} - Get user group by ID
 * - PUT /api/v1/user-groups/{groupId} - Update user group
 * - DELETE /api/v1/user-groups/{groupId} - Delete user group
 * - GET /api/v1/user-groups/{groupId}/members - List group members
 * - POST /api/v1/user-groups/{groupId}/members - Add user to group
 * - DELETE /api/v1/user-groups/{groupId}/members/{userId} - Remove user from group
 */
class UserGroupRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var userGroupTestApplication: KtorTestApp
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

    private val testUser3 = UserEntity(
        id = 3L,
        username = "user3",
        email = "user3@example.com",
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

    private val manageUserGroupsPermission = PermissionEntity(
        1L,
        CommonPermissions.MANAGE_USER_GROUPS
    )

    private val allUsersGroup = UserGroupEntity(
        id = 1L,
        name = CommonUserGroups.ALL_USERS,
        description = "Default group containing all users"
    )

    private val testGroup1 = UserGroupEntity(
        id = 2L,
        name = "Test Group 1",
        description = "First test group"
    )

    private val testGroup2 = UserGroupEntity(
        id = 3L,
        name = "Test Group 2",
        description = "Second test group"
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        userGroupTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureUserGroupRoutes(this)
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
            permissions = listOf(manageUserGroupsPermission),
            rolePermissions = listOf(
                RolePermissionEntity(
                    roleId = adminRole.id,
                    permissionId = manageUserGroupsPermission.id
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

    // ========== List User Groups Tests ==========

    @Test
    fun `GET user-groups - returns list of groups including All Users`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUserGroup(allUsersGroup)
        testDataManager.insertUserGroup(testGroup1)
        testDataManager.insertUserGroup(testGroup2)

        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(href(UserGroupResource())) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val groups = response.body<List<UserGroup>>()
        assertEquals(3, groups.size)
        assertTrue(groups.any { it.name == CommonUserGroups.ALL_USERS })
        assertTrue(groups.any { it.name == "Test Group 1" })
        assertTrue(groups.any { it.name == "Test Group 2" })
    }

    @Test
    fun `GET user-groups - returns empty list when no groups exist`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(href(UserGroupResource())) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val groups = response.body<List<UserGroup>>()
        assertEquals(0, groups.size)
    }

    @Test
    fun `GET user-groups - returns 401 when unauthenticated`() = userGroupTestApplication {
        // Act
        val response = client.get(href(UserGroupResource()))

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    @Disabled("TODO: see configureUserGroupRoutes.kt")
    fun `GET user-groups - returns 403 when user lacks MANAGE_USER_GROUPS permission`() = userGroupTestApplication {
        // Arrange
        setupStandardUser()
        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        // Act
        val response = client.get(href(UserGroupResource())) {
            bearerAuth(userToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    // ========== Create User Group Tests ==========

    @Test
    fun `POST user-groups - creates new group with unique name`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)
        val request = CreateUserGroupRequest(
            name = "New Group",
            description = "A new test group"
        )

        // Act
        val response = client.post(href(UserGroupResource())) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val createdGroup = response.body<UserGroup>()
        assertEquals("New Group", createdGroup.name)
        assertEquals("A new test group", createdGroup.description)
    }

    @Test
    fun `POST user-groups - returns 409 for duplicate name`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUserGroup(testGroup1)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)
        val request = CreateUserGroupRequest(
            name = testGroup1.name,
            description = "Duplicate name"
        )

        // Act
        val response = client.post(href(UserGroupResource())) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.code)
    }

    @Test
    fun `POST user-groups - returns 401 when unauthenticated`() = userGroupTestApplication {
        // Arrange
        val request = CreateUserGroupRequest(name = "New Group")

        // Act
        val response = client.post(href(UserGroupResource())) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST user-groups - returns 403 when user lacks permission`() = userGroupTestApplication {
        // Arrange
        setupStandardUser()
        val userToken = authHelper.createSessionAndGetToken(standardUser.id)
        val request = CreateUserGroupRequest(name = "New Group")

        // Act
        val response = client.post(href(UserGroupResource())) {
            bearerAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ========== Get User Group By ID Tests ==========

    @Test
    fun `GET user-groups by id - returns specific group`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUserGroup(testGroup1)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(href(UserGroupResource.ById(groupId = testGroup1.id))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val group = response.body<UserGroup>()
        assertEquals(testGroup1.id, group.id)
        assertEquals(testGroup1.name, group.name)
    }

    @Test
    fun `GET user-groups by id - returns 404 for non-existent group`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(href(UserGroupResource.ById(groupId = 999L))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
    }

    @Test
    fun `GET user-groups by id - returns 403 when user lacks permission`() = userGroupTestApplication {
        // Arrange
        setupStandardUser()
        testDataManager.insertUserGroup(testGroup1)
        val userToken = authHelper.createSessionAndGetToken(standardUser.id)

        // Act
        val response = client.get(href(UserGroupResource.ById(groupId = testGroup1.id))) {
            bearerAuth(userToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ========== Update User Group Tests ==========

    @Test
    fun `PUT user-groups by id - updates group name and description`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUserGroup(testGroup1)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)
        val request = UpdateUserGroupRequest(
            name = "Updated Name",
            description = "Updated description"
        )

        // Act
        val response = client.put(href(UserGroupResource.ById(groupId = testGroup1.id))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val updatedGroup = testDataManager.getUserGroup(testGroup1.id)
        assertNotNull(updatedGroup)
        assertEquals("Updated Name", updatedGroup.name)
        assertEquals("Updated description", updatedGroup.description)
    }

    @Test
    fun `PUT user-groups by id - returns 409 for duplicate name`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUserGroup(testGroup1)
        testDataManager.insertUserGroup(testGroup2)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)
        val request = UpdateUserGroupRequest(name = testGroup1.name)

        // Act
        val response = client.put(href(UserGroupResource.ById(groupId = testGroup2.id))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.code)
    }

    @Test
    fun `PUT user-groups by id - returns 404 for non-existent group`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)
        val request = UpdateUserGroupRequest(name = "Updated Name")

        // Act
        val response = client.put(href(UserGroupResource.ById(groupId = 999L))) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ========== Delete User Group Tests ==========

    @Test
    fun `DELETE user-groups by id - deletes group successfully`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUserGroup(testGroup1)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(href(UserGroupResource.ById(groupId = testGroup1.id))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE user-groups by id - returns 404 for non-existent group`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(href(UserGroupResource.ById(groupId = 999L))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE user-groups by id - returns 403 for All Users group`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUserGroup(allUsersGroup)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(href(UserGroupResource.ById(groupId = allUsersGroup.id))) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }

    // ========== Get Group Members Tests ==========

    @Test
    fun `GET user-groups members - returns list of users in group`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUser(testUser3)
        testDataManager.insertUserGroup(testGroup1)
        testDataManager.insertUserGroupMembership(adminUser.id, testGroup1.id)
        testDataManager.insertUserGroupMembership(testUser3.id, testGroup1.id)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(
            href(UserGroupResource.ById.Members(parent = UserGroupResource.ById(groupId = testGroup1.id)))
        ) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val members = response.body<List<User>>()
        assertEquals(2, members.size)
        assertTrue(members.any { it.id == adminUser.id })
        assertTrue(members.any { it.id == testUser3.id })
    }

    @Test
    fun `GET user-groups members - returns empty list for group with no members`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUserGroup(testGroup1)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.get(
            href(UserGroupResource.ById.Members(parent = UserGroupResource.ById(groupId = testGroup1.id)))
        ) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val members = response.body<List<User>>()
        assertEquals(0, members.size)
    }

    // ========== Add User to Group Tests ==========

    @Test
    fun `POST user-groups members - adds user to group successfully`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUser(testUser3)
        testDataManager.insertUserGroup(testGroup1)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)
        val request = AddUserToGroupRequest(userId = testUser3.id)

        // Act
        val response = client.post(
            href(UserGroupResource.ById.Members(parent = UserGroupResource.ById(groupId = testGroup1.id)))
        ) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `POST user-groups members - returns 409 if user already member`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUser(testUser3)
        testDataManager.insertUserGroup(testGroup1)
        testDataManager.insertUserGroupMembership(testUser3.id, testGroup1.id)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)
        val request = AddUserToGroupRequest(userId = testUser3.id)

        // Act
        val response = client.post(
            href(UserGroupResource.ById.Members(parent = UserGroupResource.ById(groupId = testGroup1.id)))
        ) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.code)
    }

    @Test
    fun `POST user-groups members - returns 400 for non-existent group`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUser(testUser3)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)
        val request = AddUserToGroupRequest(userId = testUser3.id)

        // Act
        val response = client.post(
            href(UserGroupResource.ById.Members(parent = UserGroupResource.ById(groupId = 999L)))
        ) {
            bearerAuth(adminToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ========== Remove User from Group Tests ==========

    @Test
    fun `DELETE user-groups members by userId - removes user from group successfully`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUser(testUser3)
        testDataManager.insertUserGroup(testGroup1)
        testDataManager.insertUserGroupMembership(testUser3.id, testGroup1.id)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(
            href(
                UserGroupResource.ById.Members.ByUserId(
                    parent = UserGroupResource.ById.Members(
                        parent = UserGroupResource.ById(groupId = testGroup1.id)
                    ),
                    userId = testUser3.id
                )
            )
        ) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE user-groups members by userId - returns 404 if user not member`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUser(testUser3)
        testDataManager.insertUserGroup(testGroup1)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(
            href(
                UserGroupResource.ById.Members.ByUserId(
                    parent = UserGroupResource.ById.Members(
                        parent = UserGroupResource.ById(groupId = testGroup1.id)
                    ),
                    userId = testUser3.id
                )
            )
        ) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE user-groups members by userId - returns 403 for All Users group`() = userGroupTestApplication {
        // Arrange
        setupAdminUserWithPermissions()
        testDataManager.insertUser(testUser3)
        testDataManager.insertUserGroup(allUsersGroup)
        testDataManager.insertUserGroupMembership(testUser3.id, allUsersGroup.id)
        val adminToken = authHelper.createSessionAndGetToken(adminUser.id)

        // Act
        val response = client.delete(
            href(
                UserGroupResource.ById.Members.ByUserId(
                    parent = UserGroupResource.ById.Members(
                        parent = UserGroupResource.ById(groupId = allUsersGroup.id)
                    ),
                    userId = testUser3.id
                )
            )
        ) {
            bearerAuth(adminToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.PERMISSION_DENIED.code, error.code)
    }
}
