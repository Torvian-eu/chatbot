package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.error.auth.RegisterUserError
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.PasswordService
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserServiceImplTest {

    private val userDao = mockk<UserDao>()
    private val passwordService = mockk<PasswordService>()
    private val roleDao = mockk<RoleDao>()
    private val userRoleAssignmentDao = mockk<UserRoleAssignmentDao>()
    private val userGroupService = mockk<UserGroupService>()
    private val transactionScope = mockk<TransactionScope>()

    private val userService = UserServiceImpl(userDao, passwordService, roleDao, userRoleAssignmentDao, userGroupService, transactionScope)

    private val testUserEntity = UserEntity(
        id = 1L,
        username = "testuser",
        passwordHash = "hashedpassword",
        email = "test@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastLogin = null
    )

    private val testUser = User(
        id = 1L,
        username = "testuser",
        email = "test@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastLogin = null
    )

    @BeforeEach
    fun setUp() {
        clearMocks(userDao, passwordService, transactionScope, roleDao, userRoleAssignmentDao, userGroupService)

        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @Test
    fun `registerUser should successfully register user with valid input`() = runTest {
        // Given
        val username = "newuser"
        val password = "ValidPass123!"
        val email = "new@example.com"
        val hashedPassword = "hashedValidPass123!"
        val allUsersGroup = UserGroup(
            id = 1L,
            name = CommonUserGroups.ALL_USERS,
            description = "All users group"
        )

        every { passwordService.validatePasswordStrength(password) } returns Unit.right()
        every { passwordService.hashPassword(password) } returns hashedPassword

        coEvery { userDao.insertUser(username, hashedPassword, email, UserStatus.DISABLED) } returns testUserEntity.copy(
            username = username,
            passwordHash = hashedPassword,
            email = email,
            status = UserStatus.DISABLED
        ).right()

        coEvery { userGroupService.getAllUsersGroup() } returns allUsersGroup.right()
        coEvery { userGroupService.addUserToGroup(any(), any()) } returns Unit.right()

        // When
        val result = userService.registerUser(username, password, email)

        // Then
        assertTrue(result.isRight())
        val user = result.getOrNull()!!
        assertEquals(username, user.username)
        assertEquals(email, user.email)
        assertEquals(UserStatus.DISABLED, user.status)
        // Note: User model doesn't expose passwordHash for security

        verify { passwordService.validatePasswordStrength(password) }
        verify { passwordService.hashPassword(password) }
        coVerify { userDao.insertUser(username, hashedPassword, email, any()) }
        coVerify { userGroupService.getAllUsersGroup() }
        coVerify { userGroupService.addUserToGroup(any(), allUsersGroup.id) }
    }

    @Test
    fun `registerUser should reject blank username`() = runTest {
        // Given
        val username = ""
        val password = "ValidPass123!"

        // When
        val result = userService.registerUser(username, password)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertEquals(RegisterUserError.InvalidInput("Username cannot be blank"), error)
    }

    @Test
    fun `registerUser should reject blank email when provided`() = runTest {
        // Given
        val username = "testuser"
        val password = "ValidPass123!"
        val email = ""

        // When
        val result = userService.registerUser(username, password, email)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertEquals(RegisterUserError.InvalidInput("Email cannot be blank if provided"), error)
    }

    @Test
    fun `registerUser should reject weak password`() = runTest {
        // Given
        val username = "testuser"
        val password = "weak"

        every { passwordService.validatePasswordStrength(password) } returns
                PasswordValidationError.TooShort(8, password.length).left()

        // When
        val result = userService.registerUser(username, password)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertEquals(RegisterUserError.PasswordTooWeak("Password must be at least 8 characters long"), error)
    }

    @Test
    fun `registerUser should handle username already exists error`() = runTest {
        // Given
        val username = "existinguser"
        val password = "ValidPass123!"
        val hashedPassword = "hashedValidPass123!"

        every { passwordService.validatePasswordStrength(password) } returns Unit.right()
        every { passwordService.hashPassword(password) } returns hashedPassword

        coEvery { userDao.insertUser(username, hashedPassword, null, any()) } returns
                UserError.UsernameAlreadyExists(username).left()

        // When
        val result = userService.registerUser(username, password)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertEquals(RegisterUserError.UsernameAlreadyExists(username), error)
    }

    @Test
    fun `registerUser should handle email already exists error`() = runTest {
        // Given
        val username = "testuser"
        val password = "ValidPass123!"
        val email = "existing@example.com"
        val hashedPassword = "hashedValidPass123!"

        every { passwordService.validatePasswordStrength(password) } returns Unit.right()
        every { passwordService.hashPassword(password) } returns hashedPassword

        coEvery { userDao.insertUser(username, hashedPassword, email, any()) } returns
                UserError.EmailAlreadyExists(email).left()

        // When
        val result = userService.registerUser(username, password, email)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertEquals(RegisterUserError.EmailAlreadyExists(email), error)
    }

    @Test
    fun `getUserByUsername should return user when found`() = runTest {
        // Given
        val username = "testuser"
        coEvery { userDao.getUserByUsername(username) } returns testUserEntity.right()

        // When
        val result = userService.getUserByUsername(username)

        // Then
        assertTrue(result.isRight())
        val user = result.getOrNull()!!
        assertEquals(testUser.id, user.id)
        assertEquals(testUser.username, user.username)
        assertEquals(testUser.email, user.email)
    }

    @Test
    fun `getUserByUsername should return error when not found`() = runTest {
        // Given
        val username = "nonexistent"
        coEvery { userDao.getUserByUsername(username) } returns UserError.UserNotFoundByUsername(username).left()

        // When
        val result = userService.getUserByUsername(username)

        // Then
        assertTrue(result.isLeft())
        assertEquals(UserNotFoundError.ByUsername(username), result.leftOrNull())
    }

    @Test
    fun `getUserById should return user when found`() = runTest {
        // Given
        val userId = 1L
        coEvery { userDao.getUserById(userId) } returns testUserEntity.right()

        // When
        val result = userService.getUserById(userId)

        // Then
        assertTrue(result.isRight())
        val user = result.getOrNull()!!
        assertEquals(testUser.id, user.id)
        assertEquals(testUser.username, user.username)
        assertEquals(testUser.email, user.email)
    }

    @Test
    fun `getUserById should return error when not found`() = runTest {
        // Given
        val userId = 999L
        coEvery { userDao.getUserById(userId) } returns UserError.UserNotFound(userId).left()

        // When
        val result = userService.getUserById(userId)

        // Then
        assertTrue(result.isLeft())
        assertEquals(UserNotFoundError.ById(userId), result.leftOrNull())
    }

    @Test
    fun `updateLastLogin should update successfully`() = runTest {
        // Given
        val userId = 1L
        coEvery { userDao.updateLastLogin(userId, any()) } returns Unit.right()

        // When
        val result = userService.updateLastLogin(userId)

        // Then
        assertTrue(result.isRight())
        coVerify { userDao.updateLastLogin(userId, any()) }
    }

    @Test
    fun `updateLastLogin should return error when user not found`() = runTest {
        // Given
        val userId = 999L
        coEvery { userDao.updateLastLogin(userId, any()) } returns UserError.UserNotFound(userId).left()

        // When
        val result = userService.updateLastLogin(userId)

        // Then
        assertTrue(result.isLeft())
        assertEquals(UserNotFoundError.ById(userId), result.leftOrNull())
    }

    @Test
    fun `getAllUsers should return all users`() = runTest {
        // Given
        val userEntities = listOf(testUserEntity, testUserEntity.copy(id = 2L, username = "user2"))
        coEvery { userDao.getAllUsers() } returns userEntities

        // When
        val result = userService.getAllUsers()

        // Then
        assertEquals(2, result.size)
        assertEquals(testUser.username, result[0].username)
        assertEquals("user2", result[1].username)
    }

    @Test
    fun `registerUser should add new user to All Users group`() = runTest {
        // Given
        val username = "newuser"
        val password = "ValidPass123!"
        val email = "new@example.com"
        val hashedPassword = "hashedValidPass123!"
        val allUsersGroup = UserGroup(
            id = 1L,
            name = CommonUserGroups.ALL_USERS,
            description = "All users group"
        )

        every { passwordService.validatePasswordStrength(password) } returns Unit.right()
        every { passwordService.hashPassword(password) } returns hashedPassword

        coEvery { userDao.insertUser(username, hashedPassword, email, UserStatus.DISABLED) } returns testUserEntity.copy(
            id = 2L,
            username = username,
            passwordHash = hashedPassword,
            email = email,
            status = UserStatus.DISABLED
        ).right()

        coEvery { userGroupService.getAllUsersGroup() } returns allUsersGroup.right()
        coEvery { userGroupService.addUserToGroup(2L, 1L) } returns Unit.right()

        // When
        val result = userService.registerUser(username, password, email)

        // Then
        assertTrue(result.isRight())
        coVerify { userGroupService.getAllUsersGroup() }
        coVerify { userGroupService.addUserToGroup(2L, 1L) }
    }
}
