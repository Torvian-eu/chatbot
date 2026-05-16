package eu.torvian.chatbot.app.viewmodel.auth

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.auth.AuthValidationService
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Unit tests for AuthEntryViewModel.
 *
 * Tests cover:
 * - Login functionality
 * - Registration functionality
 * - Public device verification
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthEntryViewModelTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var notificationService: NotificationService
    private lateinit var authValidationService: AuthValidationService
    private lateinit var testScope: CoroutineScope
    private lateinit var viewModel: AuthEntryViewModel

    @BeforeTest
    fun setup() {
        authRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        authValidationService = mockk(relaxed = true)
        testScope = CoroutineScope(UnconfinedTestDispatcher())

        // Set up auth state flow
        every { authRepository.authState } returns MutableStateFlow<AuthState>(AuthState.Unauthenticated)

        // Set up validation service to return no errors by default
        every { authValidationService.validateUsername(any()) } returns null
        every { authValidationService.validateEmail(any()) } returns null
        every { authValidationService.validatePassword(any()) } returns null
        every { authValidationService.validateConfirmPassword(any(), any()) } returns null

        viewModel = AuthEntryViewModel(
            authRepository = authRepository,
            notificationService = notificationService,
            normalScope = testScope,
            authValidationService = authValidationService
        )
    }

    @AfterTest
    fun tearDown() {
        clearMocks(authRepository, notificationService, authValidationService)
    }

    // ===== Login Tests =====

    @Test
    fun `login should succeed with valid credentials`() = runTest {
        // Arrange
        coEvery { authRepository.login("testuser", "password123") } returns Unit.right()

        viewModel.updateLoginForm(username = "testuser", password = "password123")

        // Act
        viewModel.login()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.login("testuser", "password123") }
        assertFalse(viewModel.loginFormState.value.isLoading)
        assertNull(viewModel.loginFormState.value.generalError)
    }

    @Test
    fun `login should show validation error for blank username`() = runTest {
        // Arrange
        every { authValidationService.validateUsername("") } returns "Username is required"

        viewModel.updateLoginForm(username = "", password = "password123")

        // Act
        viewModel.login()

        // Allow coroutine to complete
        delay(100)

        // Assert
        assertEquals("Username is required", viewModel.loginFormState.value.usernameError)
        assertTrue(viewModel.loginFormState.value.isLoading.not())
    }

    @Test
    fun `login should show validation error for blank password`() = runTest {
        // Arrange
        viewModel.updateLoginForm(username = "testuser", password = "")

        // Act
        viewModel.login()

        // Allow coroutine to complete
        delay(100)

        // Assert
        assertEquals("Password is required", viewModel.loginFormState.value.passwordError)
        assertTrue(viewModel.loginFormState.value.isLoading.not())
    }

    @Test
    fun `login should show error for invalid credentials`() = runTest {
        // Arrange
        val error = RepositoryError.DataFetchError(
            apiResourceError = ApiResourceError.ServerError(
                apiError = ApiError(
                    statusCode = 401,
                    code = "invalid-credentials",
                    message = "Invalid credentials"
                )
            ),
            contextMessage = "Login failed"
        )
        coEvery { authRepository.login("testuser", "wrongpassword") } returns error.left()

        viewModel.updateLoginForm(username = "testuser", password = "wrongpassword")

        // Act
        viewModel.login()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.login("testuser", "wrongpassword") }
        assertEquals("Invalid username or password", viewModel.loginFormState.value.generalError)
        assertFalse(viewModel.loginFormState.value.isLoading)
    }

    @Test
    fun `login should show verification trigger for verification required error`() = runTest {
        // Arrange
        val error = RepositoryError.DataFetchError(
            apiResourceError = ApiResourceError.ServerError(
                apiError = ApiError(
                    statusCode = 401,
                    code = "verification-required",
                    message = "Verification required"
                )
            ),
            contextMessage = "Login failed"
        )
        coEvery { authRepository.login("testuser", "password123") } returns error.left()

        viewModel.updateLoginForm(username = "testuser", password = "password123")

        // Act
        viewModel.login()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.login("testuser", "password123") }
        assertTrue(viewModel.loginFormState.value.showVerificationTrigger)
        assertFalse(viewModel.loginFormState.value.isLoading)
    }

    // ===== Registration Tests =====

    @Test
    fun `register should succeed with valid data`() = runTest {
        // Arrange
        val testUser = User(
            id = 1L,
            username = "newuser",
            email = "newuser@example.com",
            status = UserStatus.ACTIVE,
            createdAt = kotlin.time.Clock.System.now(),
            lastLogin = kotlin.time.Clock.System.now()
        )
        coEvery { authRepository.register("newuser", "Password123!", "newuser@example.com") } returns testUser.right()

        viewModel.updateRegisterForm(
            username = "newuser",
            email = "newuser@example.com",
            password = "Password123!",
            confirmPassword = "Password123!"
        )

        // Act
        viewModel.register()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.register("newuser", "Password123!", "newuser@example.com") }
        assertTrue(viewModel.registerFormState.value.registrationSuccessEvent)
    }

    @Test
    fun `register should show error for existing username`() = runTest {
        // Arrange
        val error = RepositoryError.DataFetchError(
            apiResourceError = ApiResourceError.ServerError(
                apiError = ApiError(
                    statusCode = 409,
                    code = "already-exists",
                    message = "Username already exists",
                    details = mapOf("field" to "username")
                )
            ),
            contextMessage = "Registration failed"
        )
        coEvery { authRepository.register("existinguser", "Password123!", null) } returns error.left()

        viewModel.updateRegisterForm(
            username = "existinguser",
            email = "",
            password = "Password123!",
            confirmPassword = "Password123!"
        )

        // Act
        viewModel.register()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.register("existinguser", "Password123!", null) }
        assertEquals(
            "Username is already taken. Please choose a different one.",
            viewModel.registerFormState.value.generalError
        )
    }

    @Test
    fun `register should show error for existing email`() = runTest {
        // Arrange
        val error = RepositoryError.DataFetchError(
            apiResourceError = ApiResourceError.ServerError(
                apiError = ApiError(
                    statusCode = 409,
                    code = "already-exists",
                    message = "Email already exists",
                    details = mapOf("field" to "email")
                )
            ),
            contextMessage = "Registration failed"
        )
        coEvery { authRepository.register("newuser", "Password123!", "existing@example.com") } returns error.left()

        viewModel.updateRegisterForm(
            username = "newuser",
            email = "existing@example.com",
            password = "Password123!",
            confirmPassword = "Password123!"
        )

        // Act
        viewModel.register()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.register("newuser", "Password123!", "existing@example.com") }
        assertEquals(
            "Email is already registered. Please use a different email or try logging in.",
            viewModel.registerFormState.value.generalError
        )
    }

    // ===== Public Device Verification Tests =====

    @Test
    fun `requestPublicVerification should succeed`() = runTest {
        // Arrange
        coEvery { authRepository.requestPublicDeviceVerification("testuser") } returns Unit.right()

        viewModel.updateLoginForm(username = "testuser", password = "password123")

        // Act
        viewModel.requestPublicVerification()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.requestPublicDeviceVerification("testuser") }
        assertTrue(viewModel.loginFormState.value.isVerificationSuccess)
        assertEquals(
            "Verification email sent! Please check your inbox and click the link to verify your device.",
            viewModel.loginFormState.value.verificationMessage
        )
    }

    @Test
    fun `requestPublicVerification should show error on failure`() = runTest {
        // Arrange
        val error = RepositoryError.DataFetchError(
            apiResourceError = ApiResourceError.ServerError(
                apiError = ApiError(
                    statusCode = 500,
                    code = "internal",
                    message = "Internal error"
                )
            ),
            contextMessage = "Verification failed"
        )
        coEvery { authRepository.requestPublicDeviceVerification("testuser") } returns error.left()

        viewModel.updateLoginForm(username = "testuser", password = "password123")

        // Act
        viewModel.requestPublicVerification()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.requestPublicDeviceVerification("testuser") }
        assertFalse(viewModel.loginFormState.value.isVerificationSuccess)
        assertNotNull(viewModel.loginFormState.value.verificationMessage)
    }

    // ===== Form State Management Tests =====

    @Test
    fun `updateLoginForm should update username`() {
        // Act
        viewModel.updateLoginForm(username = "newuser")

        // Assert
        assertEquals("newuser", viewModel.loginFormState.value.username)
    }

    @Test
    fun `updateLoginForm should update password`() {
        // Act
        viewModel.updateLoginForm(password = "newpassword")

        // Assert
        assertEquals("newpassword", viewModel.loginFormState.value.password)
    }

    @Test
    fun `updateRegisterForm should update all fields`() {
        // Act
        viewModel.updateRegisterForm(
            username = "user",
            email = "user@example.com",
            password = "pass",
            confirmPassword = "pass"
        )

        // Assert
        assertEquals("user", viewModel.registerFormState.value.username)
        assertEquals("user@example.com", viewModel.registerFormState.value.email)
        assertEquals("pass", viewModel.registerFormState.value.password)
        assertEquals("pass", viewModel.registerFormState.value.confirmPassword)
    }

    @Test
    fun `clearLoginForm should reset form state`() {
        // Arrange
        viewModel.updateLoginForm(username = "user", password = "pass")

        // Act
        viewModel.clearLoginForm()

        // Assert
        assertEquals("", viewModel.loginFormState.value.username)
        assertEquals("", viewModel.loginFormState.value.password)
        assertNull(viewModel.loginFormState.value.generalError)
    }
}
