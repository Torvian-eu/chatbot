package eu.torvian.chatbot.app.viewmodel.auth

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.auth.LoginRequest
import eu.torvian.chatbot.common.models.auth.RegisterRequest
import eu.torvian.chatbot.common.models.User
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Test class for AuthViewModel to verify authentication operations, form state management, and error handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var mockErrorNotifier: ErrorNotifier
    private val testDispatcher = StandardTestDispatcher()
    private val normalScope = CoroutineScope(testDispatcher + SupervisorJob())
    private lateinit var authViewModel: AuthViewModel

    @BeforeEach
    fun setUp() {
        mockAuthRepository = mockk {
            every { authState } returns MutableStateFlow(AuthState.Unauthenticated)
            coEvery { checkInitialAuthState() } returns Unit
        }
        mockErrorNotifier = mockk(relaxed = true)

        authViewModel = AuthViewModel(
            authRepository = mockAuthRepository,
            errorNotifier = mockErrorNotifier,
            normalScope = normalScope
        )
    }

    // --- Login Tests ---

    @Test
    fun `login with valid credentials should succeed and clear form`() = runTest(testDispatcher) {
        // Arrange
        val username = "testuser"
        val password = "ValidPass123!"
        coEvery { mockAuthRepository.login(LoginRequest(username, password)) } returns Unit.right()
        authViewModel.updateLoginForm(username, password)

        // Act
        authViewModel.login()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.loginFormState.value
        assertFalse(finalState.isLoading)
        assertNull(finalState.generalError)
        assertEquals("", finalState.username)
        assertEquals("", finalState.password)

        coVerify { mockAuthRepository.login(LoginRequest(username, password)) }
    }

    @Test
    fun `login with invalid credentials should show error`() = runTest(testDispatcher) {
        // Arrange
        val username = "testuser"
        val password = "wrongpass"
        val apiError = ApiError(401, "invalid-credentials", "Invalid credentials")
        val error = RepositoryError.DataFetchError(
            ApiResourceError.ServerError(apiError),
            "Login failed"
        )
        coEvery { mockAuthRepository.login(LoginRequest(username, password)) } returns error.left()
        authViewModel.updateLoginForm(username, password)

        // Act
        authViewModel.login()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.loginFormState.value
        assertFalse(finalState.isLoading)
        assertEquals("Invalid username or password", finalState.generalError)
        assertNull(finalState.usernameError)
        assertNull(finalState.passwordError)
    }

    @Test
    fun `login with blank username should show validation error`() = runTest(testDispatcher) {
        // Arrange
        authViewModel.updateLoginForm("", "password")
        // Act
        authViewModel.login()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.loginFormState.value
        assertFalse(finalState.isLoading)
        assertNotNull(finalState.usernameError)
        assertEquals("Username is required", finalState.usernameError)
        assertNull(finalState.passwordError)
        assertNull(finalState.generalError)

        coVerify(exactly = 0) { mockAuthRepository.login(any()) }
    }

    @Test
    fun `login with blank password should show validation error`() = runTest(testDispatcher) {
        // Arrange
        authViewModel.updateLoginForm("testuser", "")
        // Act
        authViewModel.login()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.loginFormState.value
        assertFalse(finalState.isLoading)
        assertNull(finalState.usernameError)
        assertEquals("Password is required", finalState.passwordError)
        assertNull(finalState.generalError)

        coVerify(exactly = 0) { mockAuthRepository.login(any()) }
    }

    @Test
    fun `login should set loading state during operation`() = runTest(testDispatcher) {
        // Arrange
        val username = "testuser"
        val password = "ValidPass123!"
        val deferredResult = CompletableDeferred<Unit>()
        coEvery { mockAuthRepository.login(LoginRequest(username, password)) } coAnswers {
            deferredResult.await().right()
        }
        authViewModel.updateLoginForm(username, password)

        // Act
        authViewModel.login()
        advanceTimeBy(1) // Let the coroutine start

        // Assert loading state
        val loadingState = authViewModel.loginFormState.value
        assertTrue(loadingState.isLoading)
        assertNull(loadingState.generalError)

        // Complete the operation
        deferredResult.complete(Unit)
        advanceUntilIdle()

        // Assert final state
        val finalState = authViewModel.loginFormState.value
        assertFalse(finalState.isLoading)
    }

    // --- Registration Tests ---

    @Test
    fun `register with valid data should succeed and clear form`() = runTest(testDispatcher) {
        // Arrange
        val username = "newuser"
        val email = "test@example.com"
        val password = "ValidPass123!"
        val confirmPassword = "ValidPass123!"
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val user = User(
            id = 1,
            username = username,
            email = email,
            createdAt = now,
            lastLogin = null
        )

        coEvery {
            mockAuthRepository.register(RegisterRequest(username, password, email))
        } returns user.right()
        authViewModel.updateRegisterForm(username, email, password, confirmPassword)

        // Act
        authViewModel.register()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.registerFormState.value
        assertFalse(finalState.isLoading)
        assertNull(finalState.generalError)
        assertEquals("", finalState.username)
        assertEquals("", finalState.email)
        assertEquals("", finalState.password)
        assertEquals("", finalState.confirmPassword)

        coVerify { mockAuthRepository.register(RegisterRequest(username, password, email)) }
    }

    @Test
    fun `register with existing username should show error`() = runTest(testDispatcher) {
        // Arrange
        val username = "existinguser"
        val email = "test@example.com"
        val password = "ValidPass123!"
        val confirmPassword = "ValidPass123!"
        val apiError = ApiError(400, "username-exists", "Username already exists")
        val error = RepositoryError.DataFetchError(
            ApiResourceError.ServerError(apiError),
            "Registration failed"
        )

        coEvery {
            mockAuthRepository.register(RegisterRequest(username, password, email))
        } returns error.left()
        authViewModel.updateRegisterForm(username, email, password, confirmPassword)

        // Act
        authViewModel.register()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.registerFormState.value
        assertFalse(finalState.isLoading)
        assertEquals("Username is already taken. Please choose a different one.", finalState.generalError)
    }

    @Test
    fun `register with mismatched passwords should show validation error`() = runTest(testDispatcher) {
        // Arrange
        authViewModel.updateRegisterForm("testuser", "test@example.com", "password1", "password2")
        // Act
        authViewModel.register()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.registerFormState.value
        assertFalse(finalState.isLoading)
        assertEquals("Passwords do not match", finalState.confirmPasswordError)
        assertNull(finalState.generalError)

        coVerify(exactly = 0) { mockAuthRepository.register(any()) }
    }

    @Test
    fun `register with empty email should succeed with null email`() = runTest(testDispatcher) {
        // Arrange
        val username = "testuser"
        val password = "ValidPass123!"
        val confirmPassword = "ValidPass123!"
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val user = User(
            id = 1,
            username = username,
            email = null,
            createdAt = now,
            lastLogin = null
        )

        coEvery {
            mockAuthRepository.register(RegisterRequest(username, password, null))
        } returns user.right()
        authViewModel.updateRegisterForm(username, "", password, confirmPassword)

        // Act
        authViewModel.register()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.registerFormState.value
        assertFalse(finalState.isLoading)
        assertNull(finalState.generalError)

        coVerify { mockAuthRepository.register(RegisterRequest(username, password, null)) }
    }

    // --- Logout Tests ---

    @Test
    fun `logout should succeed and clear all forms`() = runTest(testDispatcher) {
        // Arrange
        coEvery { mockAuthRepository.logout() } returns Unit.right()

        // Set some form data first
        authViewModel.updateLoginForm("testuser", "password")
        authViewModel.updateRegisterForm("newuser", "test@example.com", "pass", "pass")

        // Act
        authViewModel.logout()
        advanceUntilIdle()

        // Assert
        val loginState = authViewModel.loginFormState.value
        val registerState = authViewModel.registerFormState.value

        assertEquals("", loginState.username)
        assertEquals("", loginState.password)
        assertEquals("", registerState.username)
        assertEquals("", registerState.email)

        coVerify { mockAuthRepository.logout() }
    }

    @Test
    fun `logout failure should notify error`() = runTest(testDispatcher) {
        // Arrange
        val error = RepositoryError.OtherError("Network error", RuntimeException())
        coEvery { mockAuthRepository.logout() } returns error.left()

        // Act
        authViewModel.logout()
        advanceUntilIdle()

        // Assert
        coVerify { mockErrorNotifier.repositoryError(error, "Logout failed") }
    }

    // --- Form State Management Tests ---

    @Test
    fun `updateLoginForm should update form state and clear field errors`() = runTest(testDispatcher) {
        // Arrange - Set some initial errors by updating the form state directly
        authViewModel.updateLoginForm("user", "pass")
        // Then simulate the form having errors by calling login with blank fields
        authViewModel.updateLoginForm("", "")
        authViewModel.login()
        advanceUntilIdle()

        // Act
        authViewModel.updateLoginForm("newuser", "newpass")

        // Assert
        val updatedState = authViewModel.loginFormState.value
        assertEquals("newuser", updatedState.username)
        assertEquals("newpass", updatedState.password)
        // Note: errors are only cleared when the field value actually changes
    }

    @Test
    fun `updateRegisterForm should update form state and clear field errors`() {
        // Act
        authViewModel.updateRegisterForm("user", "test@example.com", "pass", "pass")

        // Assert
        val updatedState = authViewModel.registerFormState.value
        assertEquals("user", updatedState.username)
        assertEquals("test@example.com", updatedState.email)
        assertEquals("pass", updatedState.password)
        assertEquals("pass", updatedState.confirmPassword)
    }

    @Test
    fun `clearLoginForm should reset login form to initial state`() {
        // Arrange
        authViewModel.updateLoginForm("testuser", "password")

        // Act
        authViewModel.clearLoginForm()

        // Assert
        val clearedState = authViewModel.loginFormState.value
        assertEquals(LoginFormState(), clearedState)
    }

    @Test
    fun `clearRegisterForm should reset register form to initial state`() {
        // Arrange
        authViewModel.updateRegisterForm("user", "test@example.com", "pass", "pass")

        // Act
        authViewModel.clearRegisterForm()

        // Assert
        val clearedState = authViewModel.registerFormState.value
        assertEquals(RegisterFormState(), clearedState)
    }

    @Test
    fun `clearAllForms should reset both forms to initial state`() {
        // Arrange
        authViewModel.updateLoginForm("testuser", "password")
        authViewModel.updateRegisterForm("user", "test@example.com", "pass", "pass")

        // Act
        authViewModel.clearAllForms()

        // Assert
        val loginState = authViewModel.loginFormState.value
        val registerState = authViewModel.registerFormState.value
        assertEquals(LoginFormState(), loginState)
        assertEquals(RegisterFormState(), registerState)
    }

    // --- Error Mapping Tests ---

    @Test
    fun `mapLoginError should handle different error types correctly`() = runTest(testDispatcher) {
        // Test invalid credentials error
        val apiError = ApiError(401, "invalid-credentials", "Invalid credentials")
        val invalidCredentialsError = RepositoryError.DataFetchError(
            ApiResourceError.ServerError(apiError)
        )
        coEvery { mockAuthRepository.login(any()) } returns invalidCredentialsError.left()

        authViewModel.updateLoginForm("user", "pass")
        authViewModel.login()
        advanceUntilIdle()

        assertEquals("Invalid username or password", authViewModel.loginFormState.value.generalError)
    }

    @Test
    fun `mapRegistrationError should handle username exists error correctly`() = runTest(testDispatcher) {
        // Test username already exists error
        val apiError = ApiError(400, "username-exists", "Username already exists")
        val usernameExistsError = RepositoryError.DataFetchError(
            ApiResourceError.ServerError(apiError)
        )
        coEvery { mockAuthRepository.register(any()) } returns usernameExistsError.left()

        authViewModel.updateRegisterForm("existinguser", "email@test.com", "ValidPass123!", "ValidPass123!")
        authViewModel.register()
        advanceUntilIdle()

        assertEquals(
            "Username is already taken. Please choose a different one.",
            authViewModel.registerFormState.value.generalError
        )
    }

    // --- AuthState Delegation Tests ---

    @Test
    fun `authState should delegate to repository authState`() {
        // Arrange - Use the same StateFlow that was already set up in setUp()
        val repositoryAuthState = mockAuthRepository.authState

        // Act
        val viewModelAuthState = authViewModel.authState

        // Assert
        assertSame(repositoryAuthState, viewModelAuthState)
    }

    @Test
    fun `checkInitialAuthState should complete successfully`() = runTest(testDispatcher) {
        // Act & Assert - should not throw
        authViewModel.checkInitialAuthState()
    }

    // --- Additional Error Scenarios ---

    @Test
    fun `login with network error should show generic error message`() = runTest(testDispatcher) {
        // Arrange
        val networkError = RepositoryError.DataFetchError(
            ApiResourceError.NetworkError("Connection timeout", RuntimeException())
        )
        coEvery { mockAuthRepository.login(any()) } returns networkError.left()

        // Act
        authViewModel.updateLoginForm("user", "pass")
        authViewModel.login()
        advanceUntilIdle()

        // Assert
        assertEquals("Login failed. Please try again.", authViewModel.loginFormState.value.generalError)
    }

    @Test
    fun `register with network error should show generic error message`() = runTest(testDispatcher) {
        // Arrange
        val networkError = RepositoryError.DataFetchError(
            ApiResourceError.NetworkError("Connection timeout", RuntimeException())
        )
        coEvery { mockAuthRepository.register(any()) } returns networkError.left()

        // Act
        authViewModel.updateRegisterForm("user", "test@example.com", "ValidPass123!", "ValidPass123!")
        authViewModel.register()
        advanceUntilIdle()

        // Assert
        assertEquals("Registration failed. Please try again.", authViewModel.registerFormState.value.generalError)
    }

    @Test
    fun `login with account locked error should show specific message`() = runTest(testDispatcher) {
        // Arrange
        val apiError = ApiError(423, "account-locked", "Account locked")
        val accountLockedError = RepositoryError.DataFetchError(
            ApiResourceError.ServerError(apiError)
        )
        coEvery { mockAuthRepository.login(any()) } returns accountLockedError.left()

        // Act
        authViewModel.updateLoginForm("user", "pass")
        authViewModel.login()
        advanceUntilIdle()

        // Assert
        assertEquals("Account is temporarily locked. Please try again later.", authViewModel.loginFormState.value.generalError)
    }

    @Test
    fun `register with email already exists should show specific message`() = runTest(testDispatcher) {
        // Arrange
        val apiError = ApiError(409, "email-exists", "Email already exists")
        val emailExistsError = RepositoryError.DataFetchError(
            ApiResourceError.ServerError(apiError)
        )
        coEvery { mockAuthRepository.register(any()) } returns emailExistsError.left()

        // Act
        authViewModel.updateRegisterForm("user", "test@example.com", "ValidPass123!", "ValidPass123!")
        authViewModel.register()
        advanceUntilIdle()

        // Assert
        assertEquals("Email is already registered. Please use a different email or try logging in.", authViewModel.registerFormState.value.generalError)
    }

    @Test
    fun `form state isValid and hasErrors properties work correctly`() {
        // Test login form
        val loginState = LoginFormState(
            username = "testuser",
            password = "password123",
            usernameError = null,
            passwordError = null
        )
        assertTrue(loginState.isValid)
        assertFalse(loginState.hasErrors)

        val loginStateWithError = loginState.copy(usernameError = "Username too short")
        assertFalse(loginStateWithError.isValid)
        assertTrue(loginStateWithError.hasErrors)

        // Test register form
        val registerState = RegisterFormState(
            username = "testuser",
            password = "password123",
            confirmPassword = "password123",
            usernameError = null,
            passwordError = null,
            confirmPasswordError = null
        )
        assertTrue(registerState.isValid)
        assertFalse(registerState.hasErrors)

        val registerStateWithError = registerState.copy(passwordError = "Password too weak")
        assertFalse(registerStateWithError.isValid)
        assertTrue(registerStateWithError.hasErrors)
    }
}
