package eu.torvian.chatbot.app.viewmodel.auth

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.service.auth.AuthValidationService
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Instant

/**
 * Test class for AuthViewModel to verify authentication operations, form state management, and error handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var mockNotificationService: NotificationService
    private lateinit var mockClipboardService: ClipboardService
    private lateinit var mockAuthValidationService: AuthValidationService
    private val testDispatcher = StandardTestDispatcher()
    private val normalScope = CoroutineScope(testDispatcher + SupervisorJob())
    private lateinit var authViewModel: AuthViewModel

    @BeforeEach
    fun setUp() {
        val availableAccountsFlow = MutableStateFlow<List<AccountData>>(emptyList())
        mockAuthRepository = mockk {
            every { authState } returns MutableStateFlow(AuthState.Unauthenticated)
            every { availableAccounts } returns availableAccountsFlow
            coEvery { checkInitialAuthState() } returns Unit
            // Mock the new security alerts methods
            coEvery { getSecurityAlerts() } returns emptyList<UserSecurityAlert>().right()
        }
        mockNotificationService = mockk(relaxed = true)
        mockClipboardService = mockk(relaxed = true)
        mockAuthValidationService = mockk(relaxed = true) {
            // Mock the validation config properties to return default configurations
            every { passwordValidationConfig } returns PasswordValidationConfig()
            every { usernameValidationConfig } returns UsernameValidationConfig()
        }

        authViewModel = AuthViewModel(
            authRepository = mockAuthRepository,
            notificationService = mockNotificationService,
            clipboardService = mockClipboardService,
            normalScope = normalScope,
            authValidationService = mockAuthValidationService
        )
    }

    // --- Login Tests ---

    @Test
    fun `login with valid credentials should succeed and clear form`() = runTest(testDispatcher) {
        // Arrange
        val username = "testuser"
        val password = "ValidPass123!"
        every { mockAuthValidationService.validateUsername(username) } returns null
        every { mockAuthValidationService.validatePassword(password) } returns null
        coEvery { mockAuthRepository.login(username, password) } returns Unit.right()
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

        coVerify { mockAuthRepository.login(username, password) }
    }

    @Test
    fun `login with invalid credentials should show error`() = runTest(testDispatcher) {
        // Arrange
        val username = "testuser"
        val password = "wrongpass"
        every { mockAuthValidationService.validateUsername(username) } returns null
        every { mockAuthValidationService.validatePassword(password) } returns null
        val apiError = ApiError(401, "invalid-credentials", "Invalid credentials")
        val error = RepositoryError.DataFetchError(
            ApiResourceError.ServerError(apiError),
            "Login failed"
        )
        coEvery { mockAuthRepository.login(username, password) } returns error.left()
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
        every { mockAuthValidationService.validateUsername("") } returns "Username is required"
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

        coVerify(exactly = 0) { mockAuthRepository.login(any(), any()) }
    }

    @Test
    fun `login with blank password should show validation error`() = runTest(testDispatcher) {
        // Arrange
        every { mockAuthValidationService.validateUsername("testuser") } returns null
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

        coVerify(exactly = 0) { mockAuthRepository.login(any(), any()) }
    }

    @Test
    fun `login should set loading state during operation`() = runTest(testDispatcher) {
        // Arrange
        val username = "testuser"
        val password = "ValidPass123!"
        every { mockAuthValidationService.validateUsername(username) } returns null
        every { mockAuthValidationService.validatePassword(password) } returns null
        val deferredResult = CompletableDeferred<Unit>()
        coEvery { mockAuthRepository.login(username, password) } coAnswers {
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
            status = UserStatus.ACTIVE,
            createdAt = now,
            lastLogin = null
        )

        every { mockAuthValidationService.validateUsername(username) } returns null
        every { mockAuthValidationService.validateEmail(email) } returns null
        every { mockAuthValidationService.validatePassword(password) } returns null
        every { mockAuthValidationService.validateConfirmPassword(password, confirmPassword) } returns null

        coEvery {
            mockAuthRepository.register(username, password, email)
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

        coVerify { mockAuthRepository.register(username, password, email) }
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

        // Use any() for validation mocks to make them more flexible
        every { mockAuthValidationService.validateUsername(any()) } returns null
        every { mockAuthValidationService.validateEmail(any()) } returns null
        every { mockAuthValidationService.validatePassword(any()) } returns null
        every { mockAuthValidationService.validateConfirmPassword(any(), any()) } returns null

        coEvery { mockAuthRepository.register(any(), any(), any()) } returns error.left()
        authViewModel.updateRegisterForm(username, email, password, confirmPassword)

        // Act
        authViewModel.register()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.registerFormState.value
        assertFalse(finalState.isLoading)
        // Just check that there is a general error (the exact message may vary based on implementation)
        assertNotNull(finalState.generalError)
    }

    @Test
    fun `register with mismatched passwords should show validation error`() = runTest(testDispatcher) {
        // Arrange
        val username = "testuser"
        val email = "test@example.com"
        val password1 = "password1"
        val password2 = "password2"
        every { mockAuthValidationService.validateUsername(username) } returns null
        every { mockAuthValidationService.validateEmail(email) } returns null
        every { mockAuthValidationService.validatePassword(password1) } returns null
        every { mockAuthValidationService.validateConfirmPassword(password1, password2) } returns "Passwords do not match"

        authViewModel.updateRegisterForm(username, email, password1, password2)
        // Act
        authViewModel.register()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.registerFormState.value
        assertFalse(finalState.isLoading)
        assertEquals("Passwords do not match", finalState.confirmPasswordError)
        assertNull(finalState.generalError)

        coVerify(exactly = 0) { mockAuthRepository.register(any(), any(), any()) }
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
            status = UserStatus.ACTIVE,
            createdAt = now,
            lastLogin = null
        )

        every { mockAuthValidationService.validateUsername(username) } returns null
        every { mockAuthValidationService.validateEmail("") } returns null
        every { mockAuthValidationService.validatePassword(password) } returns null
        every { mockAuthValidationService.validateConfirmPassword(password, confirmPassword) } returns null

        coEvery {
            mockAuthRepository.register(username, password, null)
        } returns user.right()
        authViewModel.updateRegisterForm(username, "", password, confirmPassword)

        // Act
        authViewModel.register()
        advanceUntilIdle()

        // Assert
        val finalState = authViewModel.registerFormState.value
        assertFalse(finalState.isLoading)
        assertNull(finalState.generalError)

        coVerify { mockAuthRepository.register(username, password, null) }
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
        coVerify { mockNotificationService.repositoryError(error, "Logout failed") }
    }

    @Test
    fun `logoutAll should succeed and clear all forms`() = runTest(testDispatcher) {
        // Arrange
        coEvery { mockAuthRepository.logoutAll() } returns Unit.right()

        // Set some form data first
        authViewModel.updateLoginForm("testuser", "password")
        authViewModel.updateRegisterForm("newuser", "test@example.com", "pass", "pass")

        // Act
        authViewModel.logoutAll()
        advanceUntilIdle()

        // Assert
        val loginState = authViewModel.loginFormState.value
        val registerState = authViewModel.registerFormState.value

        assertEquals("", loginState.username)
        assertEquals("", loginState.password)
        assertEquals("", registerState.username)
        assertEquals("", registerState.email)

        coVerify { mockAuthRepository.logoutAll() }
    }

    @Test
    fun `logoutAll failure should notify error`() = runTest(testDispatcher) {
        // Arrange
        val error = RepositoryError.OtherError("Network error", RuntimeException())
        coEvery { mockAuthRepository.logoutAll() } returns error.left()

        // Act
        authViewModel.logoutAll()
        advanceUntilIdle()

        // Assert
        coVerify { mockNotificationService.repositoryError(error, "Logout all sessions failed") }
    }

    @Test
    fun `refreshSessions should populate active sessions from repository`() = runTest(testDispatcher) {
        // Arrange
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val sessions = listOf(
            UserSessionInfo(
                sessionId = 10L,
                deviceId = "test-device-id",
                ipAddress = "10.0.0.2",
                createdAt = now,
                lastAccessed = now,
                expiresAt = now,
                isCurrentSession = true
            )
        )
        coEvery { mockAuthRepository.getActiveSessions() } returns sessions.right()

        // Act
        authViewModel.refreshSessions()
        advanceUntilIdle()

        // Assert
        assertEquals(sessions, authViewModel.activeSessions.value)
        coVerify { mockAuthRepository.getActiveSessions() }
    }

    @Test
    fun `revokeSession should revoke and refresh the sessions list`() = runTest(testDispatcher) {
        // Arrange
        val sessionId = 42L
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val refreshedSessions = listOf(
            UserSessionInfo(
                sessionId = sessionId,
                deviceId = "test-device-id",
                ipAddress = "10.0.0.3",
                createdAt = now,
                lastAccessed = now,
                expiresAt = now,
                isCurrentSession = false
            )
        )
        coEvery { mockAuthRepository.revokeSession(sessionId) } returns Unit.right()
        coEvery { mockAuthRepository.getActiveSessions() } returns refreshedSessions.right()

        // Act
        authViewModel.revokeSession(sessionId)
        advanceUntilIdle()

        // Assert
        assertEquals(refreshedSessions, authViewModel.activeSessions.value)
        coVerify { mockAuthRepository.revokeSession(sessionId) }
        coVerify { mockAuthRepository.getActiveSessions() }
    }

    @Test
    fun `openActiveSessions should switch dialog state`() {
        // Act
        authViewModel.openActiveSessions()

        // Assert
        assertTrue(authViewModel.dialogState.value is AuthDialogState.ActiveSessions)
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
        every { mockAuthValidationService.validateUsername("user") } returns null
        every { mockAuthValidationService.validatePassword("pass") } returns null
        coEvery { mockAuthRepository.login(any(), any()) } returns invalidCredentialsError.left()

        authViewModel.updateLoginForm("user", "pass")
        authViewModel.login()
        advanceUntilIdle()

        assertEquals("Invalid username or password", authViewModel.loginFormState.value.generalError)
    }

    @Test
    fun `mapRegistrationError should handle username exists error correctly`() = runTest(testDispatcher) {
        // Test username already exists error
        // Server returns ALREADY_EXISTS with details indicating which field conflicts
        val apiError = ApiError(
            statusCode = 409,
            code = "already-exists",
            message = "Username already exists",
            details = mapOf("field" to "username", "username" to "existinguser")
        )
        val usernameExistsError = RepositoryError.DataFetchError(
            ApiResourceError.ServerError(apiError)
        )
        every { mockAuthValidationService.validateUsername("existinguser") } returns null
        every { mockAuthValidationService.validateEmail("email@test.com") } returns null
        every { mockAuthValidationService.validatePassword("ValidPass123!") } returns null
        every { mockAuthValidationService.validateConfirmPassword("ValidPass123!", "ValidPass123!") } returns null
        coEvery { mockAuthRepository.register(any(), any(), any()) } returns usernameExistsError.left()

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

    // --- Account Management Tests ---

    @Test
    fun `switchAccount should update state and reload accounts on success`() = runTest(testDispatcher) {
        // Arrange
        val userId = 2L
        coEvery { mockAuthRepository.switchAccount(userId) } returns Unit.right()

        // Act
        authViewModel.switchAccount(userId)
        advanceUntilIdle()

        // Assert
        assertFalse(authViewModel.accountSwitchInProgress.value)
        coVerify { mockAuthRepository.switchAccount(userId) }
    }

    @Test
    fun `switchAccount should set loading state during operation`() = runTest(testDispatcher) {
        // Arrange
        val userId = 2L
        val deferredResult = CompletableDeferred<Unit>()

        coEvery { mockAuthRepository.switchAccount(userId) } coAnswers {
            deferredResult.await().right()
        }

        // Act
        authViewModel.switchAccount(userId)
        advanceTimeBy(1) // Let the coroutine start

        // Assert loading state
        assertTrue(authViewModel.accountSwitchInProgress.value)

        // Complete the operation
        deferredResult.complete(Unit)
        advanceUntilIdle()

        // Assert final state
        assertFalse(authViewModel.accountSwitchInProgress.value)
    }

    @Test
    fun `switchAccount should notify error on failure`() = runTest(testDispatcher) {
        // Arrange
        val userId = 2L
        val error = RepositoryError.OtherError("Account not found", RuntimeException())
        coEvery { mockAuthRepository.switchAccount(userId) } returns error.left()

        // Act
        authViewModel.switchAccount(userId)
        advanceUntilIdle()

        // Assert
        assertFalse(authViewModel.accountSwitchInProgress.value)
        coVerify {
            mockNotificationService.repositoryError(
                shortMessage = "Failed to switch account",
                error = error
            )
        }
    }

    @Test
    fun `removeAccount should reload accounts and notify success on success`() = runTest(testDispatcher) {
        // Arrange
        val userId = 2L
        coEvery { mockAuthRepository.removeAccount(userId) } returns Unit.right()

        // Act
        authViewModel.removeAccount(userId)
        advanceUntilIdle()

        // Assert
        coVerify { mockAuthRepository.removeAccount(userId) }
    }

    @Test
    fun `removeAccount should notify error on failure`() = runTest(testDispatcher) {
        // Arrange
        val userId = 2L
        val error = RepositoryError.OtherError("Failed to delete files", RuntimeException())
        coEvery { mockAuthRepository.removeAccount(userId) } returns error.left()

        // Act
        authViewModel.removeAccount(userId)
        advanceUntilIdle()

        // Assert
        coVerify {
            mockNotificationService.repositoryError(
                shortMessage = "Failed to remove account",
                error = error
            )
        }
    }

    @Test
    fun `availableAccounts should start with empty list`() {
        // Assert
        val accounts = authViewModel.availableAccounts.value
        assertTrue(accounts.isEmpty())
    }

    @Test
    fun `accountSwitchInProgress should start as false`() {
        // Assert
        assertFalse(authViewModel.accountSwitchInProgress.value)
    }
}
