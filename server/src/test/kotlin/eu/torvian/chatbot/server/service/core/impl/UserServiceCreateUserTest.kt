package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.AccountValidationPolicy
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.dao.error.OperatorToolDefinitionError
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.CreateUserError
import eu.torvian.chatbot.server.service.core.error.auth.RegisterUserError
import eu.torvian.chatbot.server.service.core.error.tool.SeedOperatorToolsError
import eu.torvian.chatbot.server.service.core.error.usergroup.AddUserToGroupError
import eu.torvian.chatbot.server.service.security.PasswordService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for administrator-initiated account creation and the shared provisioning pipeline.
 *
 * Covers the create-path error mappings, the ACTIVE + requiresPasswordChange wiring, and
 * regression coverage proving self-registration keeps its historical contract.
 */
class UserServiceCreateUserTest {
    private lateinit var userDao: UserDao
    private lateinit var passwordService: PasswordService
    private lateinit var roleDao: RoleDao
    private lateinit var userRoleAssignmentDao: UserRoleAssignmentDao
    private lateinit var userGroupService: UserGroupService
    private lateinit var transactionScope: TransactionScope
    private lateinit var operatorToolDefinitionSeeder: OperatorToolDefinitionSeeder
    private lateinit var serverBuiltInToolDefinitionSeeder: ServerBuiltInToolDefinitionSeeder
    private lateinit var userService: UserService

    private val allUsersGroup = UserGroup(
        id = 1L,
        name = CommonUserGroups.ALL_USERS,
        description = "All users group"
    )

    private val testUserEntity = UserEntity(
        id = 2L,
        username = "newuser",
        passwordHash = "hashed",
        email = "new@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        lastLogin = null
    )

    @BeforeEach
    fun setup() {
        userDao = mockk()
        passwordService = mockk()
        roleDao = mockk()
        userRoleAssignmentDao = mockk()
        userGroupService = mockk()
        transactionScope = mockk()
        operatorToolDefinitionSeeder = mockk()
        serverBuiltInToolDefinitionSeeder = mockk()

        // Mock transaction scope to execute block directly
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        val defaultPolicy = AccountValidationPolicy(
            passwordConfig = PasswordValidationConfig(),
            usernameConfig = UsernameValidationConfig()
        )

        userService = UserServiceImpl(
            userDao,
            passwordService,
            roleDao,
            userRoleAssignmentDao,
            userGroupService,
            transactionScope,
            defaultPolicy,
            operatorToolDefinitionSeeder,
            serverBuiltInToolDefinitionSeeder
        )

        // Happy-path provisioning stubs; individual tests override the failure they exercise
        every { passwordService.validatePasswordStrength(any()) } returns Unit.right()
        every { passwordService.hashPassword(any()) } returns "hashed"
        coEvery { userDao.insertUser(any(), any(), any(), any(), any()) } returns testUserEntity.right()
        coEvery { userGroupService.getAllUsersGroup() } returns allUsersGroup.right()
        coEvery { userGroupService.addUserToGroup(any(), any()) } returns Unit.right()
        coEvery { operatorToolDefinitionSeeder.ensureForUser(any()) } returns emptyList<OperatorToolDefinition>().right()
        coEvery { serverBuiltInToolDefinitionSeeder.ensureForUser(any()) } returns emptyList<ServerBuiltInToolDefinition>().right()
    }

    // --- createUser Tests ---

    @Test
    fun `createUser provisions active user with full wiring and password change passthrough`() = runTest {
        // When
        val result = userService.createUser("newuser", "ValidPass123!", "new@example.com", requiresPasswordChange = true)

        // Then
        assertTrue(result.isRight())
        assertEquals(UserStatus.ACTIVE, result.getOrNull()!!.status)

        coVerify(exactly = 1) { userDao.insertUser("newuser", "hashed", "new@example.com", UserStatus.ACTIVE, true) }
        coVerify(exactly = 1) { userGroupService.getAllUsersGroup() }
        coVerify(exactly = 1) { userGroupService.addUserToGroup(testUserEntity.id, allUsersGroup.id) }
        coVerify(exactly = 1) { operatorToolDefinitionSeeder.ensureForUser(testUserEntity.id) }
        coVerify(exactly = 1) { serverBuiltInToolDefinitionSeeder.ensureForUser(testUserEntity.id) }

        // Creation assigns no roles; roles are managed explicitly afterwards
        verify { userRoleAssignmentDao wasNot Called }
    }

    @Test
    fun `createUser honors requiresPasswordChange false`() = runTest {
        // When
        val result = userService.createUser("newuser", "ValidPass123!", null, requiresPasswordChange = false)

        // Then
        assertTrue(result.isRight())
        coVerify(exactly = 1) { userDao.insertUser("newuser", "hashed", null, UserStatus.ACTIVE, false) }
    }

    @Test
    fun `createUser maps duplicate username`() = runTest {
        // Given
        coEvery { userDao.insertUser(any(), any(), any(), any(), any()) } returns
                UserError.UsernameAlreadyExists("newuser").left()

        // When
        val result = userService.createUser("newuser", "ValidPass123!", null)

        // Then
        val error = assertIs<CreateUserError.UsernameAlreadyExists>(result.leftOrNull())
        assertEquals("newuser", error.username)
    }

    @Test
    fun `createUser maps duplicate email`() = runTest {
        // Given
        coEvery { userDao.insertUser(any(), any(), any(), any(), any()) } returns
                UserError.EmailAlreadyExists("new@example.com").left()

        // When
        val result = userService.createUser("newuser", "ValidPass123!", "new@example.com")

        // Then
        val error = assertIs<CreateUserError.EmailAlreadyExists>(result.leftOrNull())
        assertEquals("new@example.com", error.email)
    }

    @Test
    fun `createUser rejects blank email`() = runTest {
        // When
        val result = userService.createUser("newuser", "ValidPass123!", "")

        // Then
        val error = assertIs<CreateUserError.InvalidInput>(result.leftOrNull())
        assertEquals("Email cannot be blank if provided", error.reason)
    }

    @Test
    fun `createUser maps weak password`() = runTest {
        // Given
        every { passwordService.validatePasswordStrength("abc") } returns
                PasswordValidationError.TooShort(minLength = 8, actualLength = 3).left()

        // When
        val result = userService.createUser("newuser", "abc", null)

        // Then
        val error = assertIs<CreateUserError.PasswordTooWeak>(result.leftOrNull())
        assertEquals("Password must be at least 8 characters long", error.reason)
    }

    @Test
    fun `createUser maps group assignment failure to ProvisioningFailed`() = runTest {
        // Given
        coEvery { userGroupService.addUserToGroup(any(), any()) } returns
                AddUserToGroupError.GroupNotFound(1L).left()

        // When
        val result = userService.createUser("newuser", "ValidPass123!", null)

        // Then
        val error = assertIs<CreateUserError.ProvisioningFailed>(result.leftOrNull())
        assertEquals("Failed to add user to All Users group", error.reason)
    }

    @Test
    fun `createUser maps tool seeding failure to ProvisioningFailed`() = runTest {
        // Given
        coEvery { operatorToolDefinitionSeeder.ensureForUser(any()) } returns
                SeedOperatorToolsError.LinkageFailed(OperatorToolDefinitionError.NotFound(1L)).left()

        // When
        val result = userService.createUser("newuser", "ValidPass123!", null)

        // Then
        val error = assertIs<CreateUserError.ProvisioningFailed>(result.leftOrNull())
        assertEquals("Failed to initialize user tool configuration", error.reason)
    }

    // --- registerUser regression Tests ---

    @Test
    fun `registerUser inserts disabled user without password change requirement`() = runTest {
        // Given
        coEvery { userDao.insertUser(any(), any(), any(), any(), any()) } returns
                testUserEntity.copy(status = UserStatus.DISABLED).right()

        // When
        val result = userService.registerUser("newuser", "ValidPass123!", "new@example.com")

        // Then
        assertTrue(result.isRight())
        assertEquals(UserStatus.DISABLED, result.getOrNull()!!.status)
        coVerify(exactly = 1) { userDao.insertUser("newuser", "hashed", "new@example.com", UserStatus.DISABLED, false) }
    }

    @Test
    fun `registerUser preserves group assignment failure message`() = runTest {
        // Given
        coEvery { userGroupService.addUserToGroup(any(), any()) } returns
                AddUserToGroupError.GroupNotFound(1L).left()

        // When
        val result = userService.registerUser("newuser", "ValidPass123!", null)

        // Then
        assertEquals(
            RegisterUserError.GroupAssignmentFailed("Failed to add user to All Users group"),
            result.leftOrNull()
        )
    }

    @Test
    fun `registerUser preserves tool seeding failure message`() = runTest {
        // Given
        coEvery { operatorToolDefinitionSeeder.ensureForUser(any()) } returns
                SeedOperatorToolsError.LinkageFailed(OperatorToolDefinitionError.NotFound(1L)).left()

        // When
        val result = userService.registerUser("newuser", "ValidPass123!", null)

        // Then
        assertEquals(
            RegisterUserError.InvalidInput("Failed to initialize user tool configuration"),
            result.leftOrNull()
        )
    }

    @Test
    fun `registerUser preserves weak password message`() = runTest {
        // Given
        every { passwordService.validatePasswordStrength("abc") } returns
                PasswordValidationError.TooShort(minLength = 8, actualLength = 3).left()

        // When
        val result = userService.registerUser("newuser", "abc", null)

        // Then
        assertEquals(
            RegisterUserError.PasswordTooWeak("Password must be at least 8 characters long"),
            result.leftOrNull()
        )
    }
}
