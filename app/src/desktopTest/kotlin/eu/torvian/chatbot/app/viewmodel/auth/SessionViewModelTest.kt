package eu.torvian.chatbot.app.viewmodel.auth

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.user.Permission
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Unit tests for SessionViewModel.
 *
 * Tests cover:
 * - Initial auth check on startup
 * - State delegation to repository
 * - Auth state changes propagation
 * - Session lifecycle operations (logout, logoutAll)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var notificationService: NotificationService
    private lateinit var testScope: CoroutineScope
    private lateinit var viewModel: SessionViewModel
    private lateinit var authStateFlow: MutableStateFlow<AuthState>

    private val testPermissions = listOf(
        Permission(1L, "read", "conversations"),
        Permission(2L, "write", "conversations")
    )

    @BeforeTest
    fun setup() {
        authRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        testScope = CoroutineScope(UnconfinedTestDispatcher())
        authStateFlow = MutableStateFlow(AuthState.Unauthenticated)

        // Set up auth state flow
        every { authRepository.authState } returns authStateFlow

        viewModel = SessionViewModel(
            authRepository = authRepository,
            notificationService = notificationService,
            normalScope = testScope
        )
    }

    @AfterTest
    fun tearDown() {
        clearMocks(authRepository, notificationService)
    }

    // ===== Initial Auth Check Tests =====

    @Test
    fun `checkInitialAuthState should call repository method`() {
        // Arrange
        coEvery { authRepository.checkInitialAuthState() } returns Unit

        // Act
        viewModel.checkInitialAuthState()

        // Assert
        coVerify { authRepository.checkInitialAuthState() }
    }

    // ===== State Delegation Tests =====

    @Test
    fun `authState should delegate to repository`() {
        // Assert - initial state should be Unauthenticated
        assertTrue(viewModel.authState.value is AuthState.Unauthenticated)
    }

    @Test
    fun `authState should reflect repository changes`() {
        // Arrange - set repository state to authenticated
        authStateFlow.value = AuthState.Authenticated(
            userId = 1L,
            username = "testuser",
            permissions = testPermissions,
            isRestricted = false,
            deviceId = "device-1"
        )

        // Assert - viewModel should reflect the authenticated state
        val authState = viewModel.authState.value
        assertTrue(authState is AuthState.Authenticated)
        assertEquals(1L, authState.userId)
        assertEquals("testuser", authState.username)
    }

    @Test
    fun `authState should reflect unauthenticated state`() {
        // Arrange - set repository state to authenticated first
        authStateFlow.value = AuthState.Authenticated(
            userId = 1L,
            username = "testuser",
            permissions = testPermissions,
            isRestricted = false,
            deviceId = "device-1"
        )

        // Act - transition to unauthenticated
        authStateFlow.value = AuthState.Unauthenticated

        // Assert - viewModel should reflect the unauthenticated state
        assertTrue(viewModel.authState.value is AuthState.Unauthenticated)
    }

    @Test
    fun `authState should reflect restricted authenticated state`() {
        // Arrange - set repository state to restricted authenticated
        authStateFlow.value = AuthState.Authenticated(
            userId = 1L,
            username = "testuser",
            permissions = emptyList(),
            isRestricted = true,
            deviceId = "device-1"
        )

        // Assert - viewModel should reflect the restricted state
        val authState = viewModel.authState.value
        assertTrue(authState is AuthState.Authenticated)
        assertTrue(authState.isRestricted)
    }

    @Test
    fun `authState should reflect requiresPasswordChange state`() {
        // Arrange - set repository state to require password change
        authStateFlow.value = AuthState.Authenticated(
            userId = 1L,
            username = "testuser",
            permissions = testPermissions,
            requiresPasswordChange = true,
            isRestricted = false,
            deviceId = "device-1"
        )

        // Assert - viewModel should reflect the requiresPasswordChange state
        val authState = viewModel.authState.value
        assertTrue(authState is AuthState.Authenticated)
        assertTrue(authState.requiresPasswordChange)
    }

    // ===== Logout Tests =====

    @Test
    fun `logout should call repository`() = runTest {
        // Arrange
        coEvery { authRepository.logout() } returns Unit.right()

        // Act
        viewModel.logout()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.logout() }
    }

    @Test
    fun `logout should show error notification on failure`() = runTest {
        // Arrange
        val error = mockk<RepositoryError>(relaxed = true)
        coEvery { authRepository.logout() } returns error.left()

        // Act
        viewModel.logout()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.logout() }
        coVerify { notificationService.repositoryError(error, "Logout failed") }
    }

    // ===== Logout All Tests =====

    @Test
    fun `logoutAll should call repository`() = runTest {
        // Arrange
        coEvery { authRepository.logoutAll() } returns Unit.right()

        // Act
        viewModel.logoutAll()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.logoutAll() }
    }

    @Test
    fun `logoutAll should show error notification on failure`() = runTest {
        // Arrange
        val error = mockk<RepositoryError>(relaxed = true)
        coEvery { authRepository.logoutAll() } returns error.left()

        // Act
        viewModel.logoutAll()

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.logoutAll() }
        coVerify { notificationService.repositoryError(error, "Logout all sessions failed") }
    }
}
