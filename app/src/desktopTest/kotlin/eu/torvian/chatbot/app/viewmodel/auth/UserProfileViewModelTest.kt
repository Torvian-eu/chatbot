package eu.torvian.chatbot.app.viewmodel.auth

import arrow.core.Either
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.auth.AuthValidationService
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Unit tests for UserProfileViewModel.
 *
 * Tests cover:
 * - Password change operations (normal and required)
 * - Email change operations
 * - Form validation and error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileViewModelTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var notificationService: NotificationService
    private lateinit var authValidationService: AuthValidationService
    private lateinit var testScope: CoroutineScope
    private lateinit var viewModel: UserProfileViewModel

    @BeforeTest
    fun setup() {
        authRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        authValidationService = mockk(relaxed = true)
        testScope = CoroutineScope(UnconfinedTestDispatcher())

        // Set up validation service to return no errors by default
        every { authValidationService.validatePassword(any()) } returns null
        every { authValidationService.validateConfirmPassword(any(), any()) } returns null
        every { authValidationService.validateEmail(any()) } returns null

        viewModel = UserProfileViewModel(
            authRepository = authRepository,
            notificationService = notificationService,
            authValidationService = authValidationService,
            normalScope = testScope
        )
    }

    @AfterTest
    fun tearDown() {
        clearMocks(authRepository, notificationService, authValidationService)
    }

    // ===== Password Change Tests =====

    @Test
    fun `changePassword should succeed with valid credentials`() = runTest {
        // Arrange
        coEvery { authRepository.changePassword("currentPassword", "newPassword123") } returns Either.Right(Unit)

        viewModel.updatePasswordChangeForm(
            currentPassword = "currentPassword",
            newPassword = "newPassword123",
            confirmPassword = "newPassword123"
        )

        // Act
        viewModel.changePassword()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.changePassword("currentPassword", "newPassword123") }
        assertFalse(viewModel.passwordChangeFormState.value.isLoading)
        assertTrue(viewModel.passwordChangeFormState.value.passwordChangeSuccessEvent)
    }

    @Test
    fun `changePassword should fail with invalid current password`() = runTest {
        // Arrange
        val error = RepositoryError.DataFetchError(
            apiResourceError = ApiResourceError.ServerError(
                apiError = ApiError(
                    statusCode = 401,
                    code = CommonApiErrorCodes.INVALID_CREDENTIALS.code,
                    message = "Invalid credentials"
                )
            ),
            contextMessage = "Password change failed"
        )
        coEvery { authRepository.changePassword("wrongPassword", "newPassword123") } returns Either.Left(error)

        viewModel.updatePasswordChangeForm(
            currentPassword = "wrongPassword",
            newPassword = "newPassword123",
            confirmPassword = "newPassword123"
        )

        // Act
        viewModel.changePassword()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.changePassword("wrongPassword", "newPassword123") }
        assertFalse(viewModel.passwordChangeFormState.value.isLoading)
        assertEquals("Current password is incorrect.", viewModel.passwordChangeFormState.value.generalError)
    }

    @Test
    fun `changePassword should show validation error for blank current password`() = runTest {
        // Arrange
        viewModel.updatePasswordChangeForm(
            currentPassword = "",
            newPassword = "newPassword123",
            confirmPassword = "newPassword123"
        )

        // Act
        viewModel.changePassword()

        // Allow coroutine to complete
        delay(100)

        // Assert
        assertEquals("Current password is required", viewModel.passwordChangeFormState.value.currentPasswordError)
        assertTrue(viewModel.passwordChangeFormState.value.isLoading.not())
    }

    // ===== Required Password Change Tests =====

    @Test
    fun `completeRequiredPasswordChange should succeed`() = runTest {
        // Arrange
        coEvery { authRepository.completeRequiredPasswordChange("newPassword123") } returns Either.Right(Unit)

        viewModel.updatePasswordChangeForm(
            newPassword = "newPassword123",
            confirmPassword = "newPassword123"
        )

        // Act
        viewModel.completeRequiredPasswordChange()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.completeRequiredPasswordChange("newPassword123") }
        assertFalse(viewModel.passwordChangeFormState.value.isLoading)
        assertTrue(viewModel.passwordChangeFormState.value.passwordChangeSuccessEvent)
    }

    @Test
    fun `completeRequiredPasswordChange should fail with invalid password`() = runTest {
        // Arrange
        val error = RepositoryError.DataFetchError(
            apiResourceError = ApiResourceError.ServerError(
                apiError = ApiError(
                    statusCode = 400,
                    code = CommonApiErrorCodes.INVALID_ARGUMENT.code,
                    message = "Invalid password"
                )
            ),
            contextMessage = "Password change failed"
        )
        coEvery { authRepository.completeRequiredPasswordChange("weak") } returns Either.Left(error)

        viewModel.updatePasswordChangeForm(
            newPassword = "weak",
            confirmPassword = "weak"
        )

        // Act
        viewModel.completeRequiredPasswordChange()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.completeRequiredPasswordChange("weak") }
        assertFalse(viewModel.passwordChangeFormState.value.isLoading)
        assertEquals("New password is too weak. Please choose a stronger password.", viewModel.passwordChangeFormState.value.generalError)
    }

    // ===== Email Change Tests =====

    @Test
    fun `changeEmail should succeed with valid data`() = runTest {
        // Arrange
        coEvery { authRepository.changeEmail("currentPassword", "newemail@example.com") } returns Either.Right(Unit)

        viewModel.updateChangeEmailForm(
            currentPassword = "currentPassword",
            newEmail = "newemail@example.com"
        )

        // Act
        viewModel.changeEmail()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.changeEmail("currentPassword", "newemail@example.com") }
        assertFalse(viewModel.changeEmailFormState.value.isLoading)
        assertTrue(viewModel.changeEmailFormState.value.emailChangeSuccessEvent)
    }

    @Test
    fun `changeEmail should fail with invalid email format`() = runTest {
        // Arrange
        every { authValidationService.validateEmail("invalid-email") } returns "Invalid email format"

        viewModel.updateChangeEmailForm(
            currentPassword = "currentPassword",
            newEmail = "invalid-email"
        )

        // Act
        viewModel.changeEmail()

        // Allow coroutine to complete
        delay(100)

        // Assert
        assertEquals("Invalid email format", viewModel.changeEmailFormState.value.newEmailError)
        assertTrue(viewModel.changeEmailFormState.value.isLoading.not())
    }

    @Test
    fun `changeEmail should fail with invalid current password`() = runTest {
        // Arrange
        val error = RepositoryError.DataFetchError(
            apiResourceError = ApiResourceError.ServerError(
                apiError = ApiError(
                    statusCode = 401,
                    code = CommonApiErrorCodes.INVALID_CREDENTIALS.code,
                    message = "Invalid credentials"
                )
            ),
            contextMessage = "Email change failed"
        )
        coEvery { authRepository.changeEmail("wrongPassword", "newemail@example.com") } returns Either.Left(error)

        viewModel.updateChangeEmailForm(
            currentPassword = "wrongPassword",
            newEmail = "newemail@example.com"
        )

        // Act
        viewModel.changeEmail()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.changeEmail("wrongPassword", "newemail@example.com") }
        assertFalse(viewModel.changeEmailFormState.value.isLoading)
        assertEquals("Current password is incorrect.", viewModel.changeEmailFormState.value.generalError)
    }

    // ===== Form Validation Tests =====

    @Test
    fun `changePassword should show validation error for password mismatch`() = runTest {
        // Arrange
        every { authValidationService.validateConfirmPassword("newPassword123", "differentPassword") } returns "Passwords do not match"

        viewModel.updatePasswordChangeForm(
            currentPassword = "currentPassword",
            newPassword = "newPassword123",
            confirmPassword = "differentPassword"
        )

        // Act
        viewModel.changePassword()

        // Allow coroutine to complete
        delay(100)

        // Assert
        assertEquals("Passwords do not match", viewModel.passwordChangeFormState.value.confirmPasswordError)
        assertTrue(viewModel.passwordChangeFormState.value.isLoading.not())
    }

    @Test
    fun `changeEmail should show validation error for blank current password`() = runTest {
        // Arrange
        viewModel.updateChangeEmailForm(
            currentPassword = "",
            newEmail = "newemail@example.com"
        )

        // Act
        viewModel.changeEmail()

        // Allow coroutine to complete
        delay(100)

        // Assert
        assertEquals("Current password is required", viewModel.changeEmailFormState.value.currentPasswordError)
        assertTrue(viewModel.changeEmailFormState.value.isLoading.not())
    }

    // ===== Dialog State Management Tests =====

    @Test
    fun `openChangePasswordDialog should set dialog state`() {
        // Act
        viewModel.openChangePasswordDialog()

        // Assert
        assertTrue(viewModel.dialogState.value is UserDialogState.ChangePassword)
    }

    @Test
    fun `openChangeEmailDialog should set dialog state`() {
        // Act
        viewModel.openChangeEmailDialog()

        // Assert
        assertTrue(viewModel.dialogState.value is UserDialogState.ChangeEmail)
    }

    @Test
    fun `closeDialog should set dialog state to None`() {
        // Arrange
        viewModel.openChangePasswordDialog()
        assertTrue(viewModel.dialogState.value is UserDialogState.ChangePassword)

        // Act
        viewModel.closeDialog()

        // Assert
        assertTrue(viewModel.dialogState.value is UserDialogState.None)
    }

    // ===== Form State Update Tests =====

    @Test
    fun `updatePasswordChangeForm should update fields`() {
        // Act
        viewModel.updatePasswordChangeForm(
            currentPassword = "current",
            newPassword = "new",
            confirmPassword = "confirm"
        )

        // Assert
        assertEquals("current", viewModel.passwordChangeFormState.value.currentPassword)
        assertEquals("new", viewModel.passwordChangeFormState.value.newPassword)
        assertEquals("confirm", viewModel.passwordChangeFormState.value.confirmPassword)
    }

    @Test
    fun `updateChangeEmailForm should update fields`() {
        // Act
        viewModel.updateChangeEmailForm(
            currentPassword = "current",
            newEmail = "test@example.com"
        )

        // Assert
        assertEquals("current", viewModel.changeEmailFormState.value.currentPassword)
        assertEquals("test@example.com", viewModel.changeEmailFormState.value.newEmail)
    }

    @Test
    fun `clearPasswordChangeForm should reset form state`() {
        // Arrange
        viewModel.updatePasswordChangeForm(
            currentPassword = "current",
            newPassword = "new",
            confirmPassword = "confirm"
        )

        // Act
        viewModel.clearPasswordChangeForm()

        // Assert
        assertEquals("", viewModel.passwordChangeFormState.value.currentPassword)
        assertEquals("", viewModel.passwordChangeFormState.value.newPassword)
        assertEquals("", viewModel.passwordChangeFormState.value.confirmPassword)
    }

    @Test
    fun `clearChangeEmailForm should reset form state`() {
        // Arrange
        viewModel.updateChangeEmailForm(
            currentPassword = "current",
            newEmail = "test@example.com"
        )

        // Act
        viewModel.clearChangeEmailForm()

        // Assert
        assertEquals("", viewModel.changeEmailFormState.value.currentPassword)
        assertEquals("", viewModel.changeEmailFormState.value.newEmail)
    }
}
