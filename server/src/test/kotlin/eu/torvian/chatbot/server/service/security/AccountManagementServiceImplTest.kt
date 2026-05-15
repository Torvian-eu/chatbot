package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.service.core.error.auth.ChangeEmailError
import eu.torvian.chatbot.server.service.core.error.auth.ChangePasswordError
import eu.torvian.chatbot.server.service.security.error.CompleteRequiredPasswordChangeError
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class AccountManagementServiceImplTest {

    private val userDao = mockk<UserDao>()
    private val passwordService = mockk<PasswordService>()
    private val transactionScope = mockk<TransactionScope>()

    private val accountManagementService = AccountManagementServiceImpl(
        userDao = userDao,
        passwordService = passwordService,
        transactionScope = transactionScope
    )

    private val testUser = UserEntity(
        id = 1L,
        username = "testuser",
        passwordHash = "hashedpassword",
        email = "test@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastLogin = null,
        requiresPasswordChange = false
    )

    @BeforeEach
    fun setUp() {
        clearMocks(userDao, passwordService, transactionScope)
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    // --- changePassword tests ---

    @Test
    fun `changePassword should successfully change password`() = runTest {
        // Given
        val userId = testUser.id
        val currentPassword = "oldpassword"
        val newPassword = "NewSecureP@ss123"
        coEvery { userDao.getUserById(userId) } returns testUser.right()
        every { passwordService.verifyPassword(currentPassword, testUser.passwordHash) } returns true
        every {
            passwordService.verifyPassword(
                newPassword,
                testUser.passwordHash
            )
        } returns false // New password is different from current
        every { passwordService.validatePasswordStrength(newPassword) } returns Unit.right()
        every { passwordService.hashPassword(newPassword) } returns "newhashedpassword"
        coEvery { userDao.updateUser(any()) } returns Unit.right()

        // When
        val result =
            accountManagementService.changePassword(userId, currentPassword, newPassword, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        coVerify { userDao.getUserById(userId) }
        verify { passwordService.verifyPassword(currentPassword, testUser.passwordHash) }
        verify { passwordService.validatePasswordStrength(newPassword) }
        coVerify { userDao.updateUser(match { it.passwordHash == "newhashedpassword" && !it.requiresPasswordChange }) }
    }

    @Test
    fun `changePassword should return InsufficientPermissions for restricted session`() = runTest {
        // Given
        val userId = testUser.id

        // When
        val result = accountManagementService.changePassword(userId, "old", "new", requesterIsRestricted = true)

        // Then
        assertEquals(ChangePasswordError.InsufficientPermissions, result.leftOrNull())
        coVerify(exactly = 0) { userDao.getUserById(any()) }
    }

    @Test
    fun `changePassword should return UserNotFound when user does not exist`() = runTest {
        // Given
        val userId = 999L
        coEvery { userDao.getUserById(userId) } returns UserError.UserNotFound(userId).left()

        // When
        val result = accountManagementService.changePassword(userId, "old", "new", requesterIsRestricted = false)

        // Then
        assertEquals(ChangePasswordError.UserNotFound(userId), result.leftOrNull())
    }

    @Test
    fun `changePassword should return InvalidCurrentPassword when password is wrong`() = runTest {
        // Given
        val userId = testUser.id
        coEvery { userDao.getUserById(userId) } returns testUser.right()
        every { passwordService.verifyPassword("wrongpassword", testUser.passwordHash) } returns false

        // When
        val result = accountManagementService.changePassword(
            userId,
            "wrongpassword",
            "newpassword",
            requesterIsRestricted = false
        )

        // Then
        assertEquals(ChangePasswordError.InvalidCurrentPassword, result.leftOrNull())
    }

    @Test
    fun `changePassword should return InvalidPassword when new password is weak`() = runTest {
        // Given
        val userId = testUser.id
        coEvery { userDao.getUserById(userId) } returns testUser.right()
        every { passwordService.verifyPassword("oldpassword", testUser.passwordHash) } returns true
        every { passwordService.validatePasswordStrength("weak") } returns PasswordValidationError.TooShort(8, 4).left()

        // When
        val result =
            accountManagementService.changePassword(userId, "oldpassword", "weak", requesterIsRestricted = false)

        // Then
        assertTrue(result.isLeft())
        assertIs<ChangePasswordError.InvalidPassword>(result.leftOrNull())
    }

    @Test
    fun `changePassword should return SameAsCurrentPassword when reusing current password`() = runTest {
        // Given
        val userId = testUser.id
        val currentPassword = "oldpassword"
        coEvery { userDao.getUserById(userId) } returns testUser.right()
        every { passwordService.verifyPassword(currentPassword, testUser.passwordHash) } returns true
        every { passwordService.validatePasswordStrength(currentPassword) } returns Unit.right()
        every { passwordService.verifyPassword(currentPassword, testUser.passwordHash) } returns true

        // When
        val result = accountManagementService.changePassword(
            userId,
            currentPassword,
            currentPassword,
            requesterIsRestricted = false
        )

        // Then
        assertEquals(ChangePasswordError.SameAsCurrentPassword, result.leftOrNull())
    }

    // --- changeEmail tests ---

    @Test
    fun `changeEmail should successfully change email`() = runTest {
        // Given
        val userId = testUser.id
        val currentPassword = "password"
        val newEmail = "newemail@example.com"
        coEvery { userDao.emailExists(newEmail, userId) } returns false
        coEvery { userDao.getUserById(userId) } returns testUser.right()
        every { passwordService.verifyPassword(currentPassword, testUser.passwordHash) } returns true
        coEvery { userDao.updateUser(any()) } returns Unit.right()

        // When
        val result =
            accountManagementService.changeEmail(userId, currentPassword, newEmail, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        val updatedUser = result.getOrNull()!!
        assertEquals(newEmail, updatedUser.email)
        coVerify { userDao.emailExists(newEmail, userId) }
        coVerify { userDao.updateUser(match { it.email == newEmail }) }
    }

    @Test
    fun `changeEmail should return InsufficientPermissions for restricted session`() = runTest {
        // Given
        val userId = testUser.id

        // When
        val result =
            accountManagementService.changeEmail(userId, "password", "new@example.com", requesterIsRestricted = true)

        // Then
        assertEquals(ChangeEmailError.InsufficientPermissions, result.leftOrNull())
    }

    @Test
    fun `changeEmail should return InvalidEmailFormat for invalid email`() = runTest {
        // Given
        val userId = testUser.id
        coEvery { userDao.getUserById(userId) } returns testUser.right()

        // When
        val result =
            accountManagementService.changeEmail(userId, "password", "invalid-email", requesterIsRestricted = false)

        // Then
        assertTrue(result.isLeft())
        assertIs<ChangeEmailError.InvalidEmailFormat>(result.leftOrNull())
    }

    @Test
    fun `changeEmail should return EmailAlreadyExists when email is taken`() = runTest {
        // Given
        val userId = testUser.id
        val newEmail = "taken@example.com"
        coEvery { userDao.emailExists(newEmail, userId) } returns true

        // When
        val result = accountManagementService.changeEmail(userId, "password", newEmail, requesterIsRestricted = false)

        // Then
        assertEquals(ChangeEmailError.EmailAlreadyExists(newEmail), result.leftOrNull())
    }

    @Test
    fun `changeEmail should return InvalidCurrentPassword when password is wrong`() = runTest {
        // Given
        val userId = testUser.id
        coEvery { userDao.emailExists("new@example.com", userId) } returns false
        coEvery { userDao.getUserById(userId) } returns testUser.right()
        every { passwordService.verifyPassword("wrong", testUser.passwordHash) } returns false

        // When
        val result =
            accountManagementService.changeEmail(userId, "wrong", "new@example.com", requesterIsRestricted = false)

        // Then
        assertEquals(ChangeEmailError.InvalidCurrentPassword, result.leftOrNull())
    }

    // --- completeRequiredPasswordChange tests ---

    @Test
    fun `completeRequiredPasswordChange should successfully change required password`() = runTest {
        // Given
        val userRequiringChange = testUser.copy(requiresPasswordChange = true)
        val userId = userRequiringChange.id
        val newPassword = "NewSecureP@ss123"
        coEvery { userDao.getUserById(userId) } returns userRequiringChange.right()
        every { passwordService.validatePasswordStrength(newPassword) } returns Unit.right()
        every { passwordService.hashPassword(newPassword) } returns "newhashedpassword"
        coEvery { userDao.updateUser(any()) } returns Unit.right()

        // When
        val result =
            accountManagementService.completeRequiredPasswordChange(userId, newPassword, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        coVerify { userDao.updateUser(match { it.passwordHash == "newhashedpassword" && !it.requiresPasswordChange }) }
    }

    @Test
    fun `completeRequiredPasswordChange should return InsufficientPermissions for restricted session`() = runTest {
        // Given
        val userId = testUser.id

        // When
        val result =
            accountManagementService.completeRequiredPasswordChange(userId, "newpassword", requesterIsRestricted = true)

        // Then
        assertEquals(CompleteRequiredPasswordChangeError.InsufficientPermissions, result.leftOrNull())
    }

    @Test
    fun `completeRequiredPasswordChange should return PasswordChangeNotRequired when not required`() = runTest {
        // Given
        val userId = testUser.id
        coEvery { userDao.getUserById(userId) } returns testUser.right()

        // When
        val result = accountManagementService.completeRequiredPasswordChange(
            userId,
            "newpassword",
            requesterIsRestricted = false
        )

        // Then
        assertEquals(CompleteRequiredPasswordChangeError.PasswordChangeNotRequired, result.leftOrNull())
    }

    @Test
    fun `completeRequiredPasswordChange should return UserNotFound when user does not exist`() = runTest {
        // Given
        val userId = 999L
        coEvery { userDao.getUserById(userId) } returns UserError.UserNotFound(userId).left()

        // When
        val result = accountManagementService.completeRequiredPasswordChange(
            userId,
            "newpassword",
            requesterIsRestricted = false
        )

        // Then
        assertEquals(CompleteRequiredPasswordChangeError.UserNotFound, result.leftOrNull())
    }

    @Test
    fun `completeRequiredPasswordChange should return WeakPassword for weak password`() = runTest {
        // Given
        val userRequiringChange = testUser.copy(requiresPasswordChange = true)
        val userId = userRequiringChange.id
        coEvery { userDao.getUserById(userId) } returns userRequiringChange.right()
        every { passwordService.validatePasswordStrength("weak") } returns PasswordValidationError.TooShort(8, 4).left()

        // When
        val result =
            accountManagementService.completeRequiredPasswordChange(userId, "weak", requesterIsRestricted = false)

        // Then
        assertTrue(result.isLeft())
        assertIs<CompleteRequiredPasswordChangeError.WeakPassword>(result.leftOrNull())
    }
}
