package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.dao.error.UserRoleAssignmentError
import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.*
import eu.torvian.chatbot.server.service.security.PasswordService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class UserServiceAdminTest {
    private lateinit var userDao: UserDao
    private lateinit var passwordService: PasswordService
    private lateinit var roleDao: RoleDao
    private lateinit var userRoleAssignmentDao: UserRoleAssignmentDao
    private lateinit var userGroupService: UserGroupService
    private lateinit var transactionScope: TransactionScope
    private lateinit var userService: UserService

    @BeforeEach
    fun setup() {
        userDao = mockk()
        passwordService = mockk()
        roleDao = mockk()
        userRoleAssignmentDao = mockk()
        userGroupService = mockk()
        transactionScope = mockk()

        // Mock transaction scope to execute block directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        userService = UserServiceImpl(
            userDao,
            passwordService,
            roleDao,
            userRoleAssignmentDao,
            userGroupService,
            transactionScope
        )
    }

    // --- updateUser Tests ---

    @Test
    fun `updateUser should update user successfully`() = runTest {
        // Given
        val userId = 1L
        val newUsername = "newusername"
        val newEmail = "new@example.com"

        val existingUser = UserEntity(
            id = userId,
            username = "oldusername",
            passwordHash = "hash",
            email = "old@example.com",
            status = UserStatus.ACTIVE,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            lastLogin = null
        )

        coEvery { userDao.getUserById(userId) } returns existingUser.right()
        coEvery { userDao.updateUser(any()) } returns Unit.right()

        // When
        val result = userService.updateUser(userId, newUsername, newEmail)

        // Then
        assertTrue(result.isRight())
        val user = result.getOrNull()!!
        assertEquals(newUsername, user.username)
        assertEquals(newEmail, user.email)

        coVerify { userDao.updateUser(match { it.username == newUsername && it.email == newEmail }) }
    }

    @Test
    fun `updateUser should return error when user not found`() = runTest {
        // Given
        val userId = 999L

        coEvery { userDao.getUserById(userId) } returns UserError.UserNotFound(userId).left()

        // When
        val result = userService.updateUser(userId, "newname", "new@example.com")

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is UpdateUserError.UserNotFound)
        assertEquals(userId, (error as UpdateUserError.UserNotFound).userId)
    }

    @Test
    fun `updateUser should return error when username already exists`() = runTest {
        // Given
        val userId = 1L
        val existingUsername = "existinguser"

        val existingUser = UserEntity(
            id = userId,
            username = "oldusername",
            passwordHash = "hash",
            email = null,
            status = UserStatus.ACTIVE,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            lastLogin = null
        )

        coEvery { userDao.getUserById(userId) } returns existingUser.right()
        coEvery { userDao.updateUser(any()) } returns
                UserError.UsernameAlreadyExists(existingUsername).left()

        // When
        val result = userService.updateUser(userId, existingUsername, null)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is UpdateUserError.UsernameAlreadyExists)
        assertEquals(existingUsername, (error as UpdateUserError.UsernameAlreadyExists).username)
    }

    @Test
    fun `updateUser should return error when username is blank`() = runTest {
        // When
        val result = userService.updateUser(1L, "", null)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is UpdateUserError.InvalidInput)
    }

    // --- deleteUser Tests ---

    @Test
    fun `deleteUser should delete user successfully`() = runTest {
        // Given
        val userId = 2L

        val user = UserEntity(
            id = userId,
            username = "testuser",
            passwordHash = "hash",
            email = null,
            status = UserStatus.ACTIVE,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            lastLogin = null
        )

        val adminRole = RoleEntity(1L, "Admin", "Admin role")

        coEvery { userDao.getUserById(userId) } returns user.right()
        coEvery { roleDao.getRoleByName("Admin") } returns adminRole.right()
        coEvery { userRoleAssignmentDao.getUserIdsByRoleId(adminRole.id) } returns
                listOf(1L, 2L) // Multiple admins
        coEvery { userDao.deleteUser(userId) } returns Unit.right()

        // When
        val result = userService.deleteUser(userId)

        // Then
        assertTrue(result.isRight())
        coVerify { userDao.deleteUser(userId) }
    }

    @Test
    fun `deleteUser should return error when user is last admin`() = runTest {
        // Given
        val userId = 1L

        val user = UserEntity(
            id = userId,
            username = "admin",
            passwordHash = "hash",
            email = null,
            status = UserStatus.ACTIVE,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            lastLogin = null
        )

        val adminRole = RoleEntity(1L, "Admin", "Admin role")

        coEvery { userDao.getUserById(userId) } returns user.right()
        coEvery { roleDao.getRoleByName("Admin") } returns adminRole.right()
        coEvery { userRoleAssignmentDao.getUserIdsByRoleId(adminRole.id) } returns
                listOf(userId) // Only one admin

        // When
        val result = userService.deleteUser(userId)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is DeleteUserError.CannotDeleteLastAdmin)
        assertEquals(userId, (error as DeleteUserError.CannotDeleteLastAdmin).userId)

        coVerify(exactly = 0) { userDao.deleteUser(any()) }
    }

    @Test
    fun `deleteUser should return error when user not found`() = runTest {
        // Given
        val userId = 999L

        coEvery { userDao.getUserById(userId) } returns UserError.UserNotFound(userId).left()

        // When
        val result = userService.deleteUser(userId)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is DeleteUserError.UserNotFound)
    }

    // --- assignRoleToUser Tests ---

    @Test
    fun `assignRoleToUser should assign role successfully`() = runTest {
        // Given
        val userId = 1L
        val roleId = 2L

        coEvery { userRoleAssignmentDao.assignRoleToUser(userId, roleId) } returns Unit.right()

        // When
        val result = userService.assignRoleToUser(userId, roleId)

        // Then
        assertTrue(result.isRight())
        coVerify { userRoleAssignmentDao.assignRoleToUser(userId, roleId) }
    }

    @Test
    fun `assignRoleToUser should return error when role already assigned`() = runTest {
        // Given
        val userId = 1L
        val roleId = 2L

        coEvery { userRoleAssignmentDao.assignRoleToUser(userId, roleId) } returns
                UserRoleAssignmentError.AssignmentAlreadyExists(userId, roleId).left()

        // When
        val result = userService.assignRoleToUser(userId, roleId)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is AssignRoleError.RoleAlreadyAssigned)
    }

    // --- revokeRoleFromUser Tests ---

    @Test
    fun `revokeRoleFromUser should revoke role successfully`() = runTest {
        // Given
        val userId = 2L
        val roleId = 3L

        val role = RoleEntity(roleId, "StandardUser", "Standard user role")

        coEvery { roleDao.getRoleById(roleId) } returns role.right()
        coEvery { userRoleAssignmentDao.revokeRoleFromUser(userId, roleId) } returns Unit.right()

        // When
        val result = userService.revokeRoleFromUser(userId, roleId)

        // Then
        assertTrue(result.isRight())
        coVerify { userRoleAssignmentDao.revokeRoleFromUser(userId, roleId) }
    }

    @Test
    fun `revokeRoleFromUser should return error when revoking admin from last admin`() = runTest {
        // Given
        val userId = 1L
        val adminRoleId = 1L

        val adminRole = RoleEntity(adminRoleId, "Admin", "Admin role")

        coEvery { roleDao.getRoleById(adminRoleId) } returns adminRole.right()
        coEvery { roleDao.getRoleByName("Admin") } returns adminRole.right()
        coEvery { userRoleAssignmentDao.getUserIdsByRoleId(adminRoleId) } returns
                listOf(userId) // Only one admin

        // When
        val result = userService.revokeRoleFromUser(userId, adminRoleId)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is RevokeRoleError.CannotRevokeLastAdminRole)

        coVerify(exactly = 0) { userRoleAssignmentDao.revokeRoleFromUser(any(), any()) }
    }

    // --- changePassword Tests ---

    @Test
    fun `changePassword should change password successfully`() = runTest {
        // Given
        val userId = 1L
        val newPassword = "NewPass123!"
        val hashedPassword = "hashedNewPass123!"

        val existingUser = UserEntity(
            id = userId,
            username = "testuser",
            passwordHash = "oldHash",
            email = null,
            status = UserStatus.ACTIVE,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            lastLogin = null
        )

        every { passwordService.validatePasswordStrength(newPassword) } returns Unit.right()
        every { passwordService.hashPassword(newPassword) } returns hashedPassword
        // Ensure verifyPassword returns false for a new/different password
        every { passwordService.verifyPassword(newPassword, existingUser.passwordHash) } returns false
        coEvery { userDao.getUserById(userId) } returns existingUser.right()
        coEvery { userDao.updateUser(any()) } returns Unit.right()

        // When
        val result = userService.changePassword(userId, newPassword)

        // Then
        assertTrue(result.isRight())
        coVerify { userDao.updateUser(match { it.passwordHash == hashedPassword }) }
    }

    @Test
    fun `changePassword should return error when password is invalid`() = runTest {
        // Given
        val userId = 1L
        val weakPassword = "weak"

        every { passwordService.validatePasswordStrength(weakPassword) } returns
                eu.torvian.chatbot.common.security.error.PasswordValidationError.TooShort(8, weakPassword.length).left()

        // When
        val result = userService.changePassword(userId, weakPassword)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is ChangePasswordError.InvalidPassword)
    }

    @Test
    fun `changePassword should reject password same as current`() = runTest {
        // Given
        val userId = 1L
        val currentPassword = "CurrentPass123!"
        val hashedCurrentPassword = "hashedCurrentPass"

        val existingUser = UserEntity(
            id = userId,
            username = "testuser",
            passwordHash = hashedCurrentPassword,
            email = null,
            status = UserStatus.ACTIVE,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
            lastLogin = null
        )

        every { passwordService.validatePasswordStrength(currentPassword) } returns Unit.right()
        every { passwordService.verifyPassword(currentPassword, hashedCurrentPassword) } returns true
        coEvery { userDao.getUserById(userId) } returns existingUser.right()

        // When
        val result = userService.changePassword(userId, currentPassword)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is ChangePasswordError.SameAsCurrentPassword)
    }

    // --- getUserRoles Tests ---

    @Test
    fun `getUserRoles should return roles for user`() = runTest {
        // Given
        val userId = 42L
        val roles = listOf(
            RoleEntity(1L, "Admin", "Admin role"),
            RoleEntity(2L, "StandardUser", "Standard user role")
        )
        coEvery { userRoleAssignmentDao.getRolesByUserId(userId) } returns roles

        // When
        val result: List<Role> = userService.getUserRoles(userId)

        // Then
        assertEquals(2, result.size)
        assertEquals(setOf("Admin", "StandardUser"), result.map { it.name }.toSet())
    }
}
