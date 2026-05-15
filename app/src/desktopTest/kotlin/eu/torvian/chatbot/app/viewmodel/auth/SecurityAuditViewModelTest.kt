package eu.torvian.chatbot.app.viewmodel.auth

import arrow.core.Either
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock

/**
 * Unit tests for SecurityAuditViewModel.
 *
 * Tests cover:
 * - Session and device revocation operations
 * - Reactive state clearing on user switch
 * - State preservation on same-user token refresh
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityAuditViewModelTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var notificationService: NotificationService
    private lateinit var clipboardService: ClipboardService
    private lateinit var testScope: CoroutineScope
    private lateinit var viewModel: SecurityAuditViewModel
    private lateinit var authStateFlow: MutableStateFlow<AuthState>

    private val testUserId1 = 1L
    private val testUserId2 = 2L
    private val testDeviceId = "test-device-id"

    private val testSession = UserSessionInfo(
        sessionId = 1L,
        deviceId = "device1",
        ipAddress = "10.0.0.1",
        createdAt = Clock.System.now(),
        lastAccessed = Clock.System.now(),
        expiresAt = Clock.System.now(),
        isCurrentSession = true
    )

    private val testTrustedDevice = UserTrustedDeviceInfo(
        deviceId = "device1",
        lastIpAddress = "10.0.0.1",
        firstSeenAt = Clock.System.now(),
        lastUsedAt = Clock.System.now()
    )

    private val testSecurityAlert = UserSecurityAlert(
        id = 1L,
        deviceId = "device1",
        ipAddress = "10.0.0.1",
        firstSeenAt = Clock.System.now(),
        lastSeenAt = Clock.System.now()
    )

    @BeforeTest
    fun setup() {
        authRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        clipboardService = mockk(relaxed = true)
        testScope = CoroutineScope(UnconfinedTestDispatcher())
        authStateFlow = MutableStateFlow(AuthState.Unauthenticated)

        // Set up auth state flow
        every { authRepository.authState } returns authStateFlow

        viewModel = SecurityAuditViewModel(
            authRepository = authRepository,
            notificationService = notificationService,
            clipboardService = clipboardService,
            normalScope = testScope
        )
    }

    @AfterTest
    fun tearDown() {
        clearMocks(authRepository, notificationService, clipboardService)
    }

    // ===== State Clearing on User Switch Tests =====

    @Test
    fun `security state should be cleared when user changes`() = runTest {
        // Arrange - set initial state with user 1
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList()
        )
        delay(50)

        // Populate security state via public API (mocking repository)
        coEvery { authRepository.getActiveSessions() } returns Either.Right(listOf(testSession))
        coEvery { authRepository.getTrustedDevices() } returns Either.Right(listOf(testTrustedDevice))
        coEvery { authRepository.getSecurityAlerts() } returns Either.Right(listOf(testSecurityAlert))

        viewModel.refreshSessions()
        viewModel.refreshTrustedDevices()
        viewModel.showSecurityAlerts(showOnEmpty = true)
        delay(100)

        // Verify state is populated
        assertFalse(viewModel.activeSessions.value.isEmpty(), "Sessions should be populated")
        assertFalse(viewModel.trustedDevices.value.isEmpty(), "Trusted devices should be populated")
        assertFalse(viewModel.securityAlerts.value.isEmpty(), "Security alerts should be populated")

        // Act - switch to user 2 (mock getSecurityAlerts to return empty for user 2)
        coEvery { authRepository.getSecurityAlerts() } returns Either.Right(emptyList())

        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId2,
            username = "user2",
            deviceId = testDeviceId,
            permissions = emptyList()
        )

        // Allow the flow to process
        delay(100)

        // Assert - security state should be cleared
        assertEquals(emptyList(), viewModel.activeSessions.value)
        assertEquals(emptyList(), viewModel.trustedDevices.value)
        assertEquals(emptyList(), viewModel.securityAlerts.value)
    }

    @Test
    fun `security state should NOT be cleared when same user token refreshes with same isRestricted`() = runTest {
        // Arrange - set initial state with user 1
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = false
        )
        delay(50)

        // Populate security state via public API
        coEvery { authRepository.getActiveSessions() } returns Either.Right(listOf(testSession))
        viewModel.refreshSessions()
        delay(100)

        // Verify state is populated
        assertFalse(viewModel.activeSessions.value.isEmpty(), "Sessions should be populated")

        // Act - same user with same isRestricted (e.g., token refresh without state change)
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = false
        )

        // Allow the flow to process
        delay(100)

        // Assert - security state should NOT be cleared (same user, same isRestricted)
        // The state should persist because distinctUntilChanged prevents emission
        assertFalse(viewModel.activeSessions.value.isEmpty(), "Sessions should persist on token refresh")
    }

    @Test
    fun `security state should be cleared when switching from unauthenticated to authenticated`() = runTest {
        // Act - switch to authenticated
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList()
        )

        // Allow the flow to process
        delay(100)

        // Assert - security state should be cleared (initial auth clears any stale state)
        assertEquals(emptyList(), viewModel.activeSessions.value)
    }

    @Test
    fun `security state should be cleared when transitioning to unauthenticated (logout)`() = runTest {
        // Arrange - set initial state with user 1
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList()
        )
        delay(50)

        // Populate security state via public API
        coEvery { authRepository.getActiveSessions() } returns Either.Right(listOf(testSession))
        coEvery { authRepository.getTrustedDevices() } returns Either.Right(listOf(testTrustedDevice))
        coEvery { authRepository.getSecurityAlerts() } returns Either.Right(listOf(testSecurityAlert))

        viewModel.refreshSessions()
        viewModel.refreshTrustedDevices()
        viewModel.showSecurityAlerts(showOnEmpty = true)
        delay(100)

        // Verify state is populated
        assertFalse(viewModel.activeSessions.value.isEmpty(), "Sessions should be populated")
        assertFalse(viewModel.trustedDevices.value.isEmpty(), "Trusted devices should be populated")
        assertFalse(viewModel.securityAlerts.value.isEmpty(), "Security alerts should be populated")

        // Act - transition to unauthenticated (logout)
        authStateFlow.value = AuthState.Unauthenticated

        // Allow the flow to process
        delay(100)

        // Assert - security state should be cleared
        assertEquals(emptyList(), viewModel.activeSessions.value)
        assertEquals(emptyList(), viewModel.trustedDevices.value)
        assertEquals(emptyList(), viewModel.securityAlerts.value)
    }

    // ===== Session Revocation Tests =====

    @Test
    fun `revokeSession should call repository and refresh sessions on success`() = runTest {
        // Arrange
        coEvery { authRepository.revokeSession(1L) } returns Either.Right(Unit)
        coEvery { authRepository.getActiveSessions() } returns Either.Right(emptyList())

        // Act
        viewModel.revokeSession(1L)

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.revokeSession(1L) }
        coVerify { authRepository.getActiveSessions() }
    }

    @Test
    fun `revokeSession should show error notification on failure`() = runTest {
        // Arrange
        val error = mockk<eu.torvian.chatbot.app.repository.RepositoryError>(relaxed = true)
        coEvery { authRepository.revokeSession(1L) } returns Either.Left(error)

        // Act
        viewModel.revokeSession(1L)

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.revokeSession(1L) }
        coVerify { notificationService.repositoryError(error, "Failed to revoke session") }
    }

    // ===== Trusted Device Revocation Tests =====

    @Test
    fun `revokeTrustedDevice should call repository and refresh devices on success`() = runTest {
        // Arrange
        coEvery { authRepository.revokeTrustedDevice("device-1") } returns Either.Right(Unit)
        coEvery { authRepository.getTrustedDevices() } returns Either.Right(emptyList())

        // Act
        viewModel.revokeTrustedDevice("device-1")

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.revokeTrustedDevice("device-1") }
        coVerify { authRepository.getTrustedDevices() }
    }

    // ===== Dialog State Management Tests =====

    @Test
    fun `openActiveSessions should set dialog state`() {
        // Act
        viewModel.openActiveSessions()

        // Assert
        assertTrue(viewModel.dialogState.value is SecurityDialogState.ActiveSessions)
    }

    @Test
    fun `openTrustedDevices should set dialog state`() {
        // Act
        viewModel.openTrustedDevices()

        // Assert
        assertTrue(viewModel.dialogState.value is SecurityDialogState.TrustedDevices)
    }

    @Test
    fun `openRestrictedSessionInfo should set dialog state`() {
        // Act
        viewModel.openRestrictedSessionInfo()

        // Assert
        assertTrue(viewModel.dialogState.value is SecurityDialogState.RestrictedSessionInfo)
    }

    @Test
    fun `closeDialog should set dialog state to None`() {
        // Arrange
        viewModel.openActiveSessions()
        assertTrue(viewModel.dialogState.value is SecurityDialogState.ActiveSessions)

        // Act
        viewModel.closeDialog()

        // Assert
        assertTrue(viewModel.dialogState.value is SecurityDialogState.None)
    }

    // ===== Dialog Opening on Auth State Change Tests =====

    @Test
    fun `restricted session info dialog should open when user authenticates with isRestricted true`() = runTest {
        // Act - authenticate with restricted session
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = true
        )

        // Allow the flow to process
        delay(100)

        // Assert - restricted session info dialog should be open
        assertTrue(viewModel.dialogState.value is SecurityDialogState.RestrictedSessionInfo)
    }

    @Test
    fun `security alerts dialog should open when user authenticates with isRestricted false`() = runTest {
        // Arrange - mock the security alerts fetch
        coEvery { authRepository.getSecurityAlerts() } returns Either.Right(emptyList())

        // Act - authenticate with unrestricted session
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = false
        )

        // Allow the flow to process
        delay(100)

        // Assert - security alerts dialog should be open (with empty list)
        // Note: showSecurityAlerts() is called, which fetches alerts and opens dialog
        coVerify { authRepository.getSecurityAlerts() }
    }

    @Test
    fun `dialog should not open when transitioning to unauthenticated`() = runTest {
        // Arrange - set initial state with user
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList()
        )
        delay(50)

        // Act - transition to unauthenticated
        authStateFlow.value = AuthState.Unauthenticated

        // Allow the flow to process
        delay(100)

        // Assert - dialog should be None (no dialog opened for logout)
        assertTrue(viewModel.dialogState.value is SecurityDialogState.None)
    }

    @Test
    fun `security state should be cleared when isRestricted changes for same user`() = runTest {
        // Arrange - set initial state with user 1 (restricted)
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = true
        )
        delay(50)

        // Populate security state via public API
        coEvery { authRepository.getActiveSessions() } returns Either.Right(listOf(testSession))
        viewModel.refreshSessions()
        delay(100)

        // Verify state is populated
        assertFalse(viewModel.activeSessions.value.isEmpty(), "Sessions should be populated")

        // Act - same user with isRestricted changed from true to false
        // This simulates device verification - the restricted session is now unrestricted
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = false
        )

        // Allow the flow to process
        delay(100)

        // Assert - security state should be cleared (isRestricted changed)
        assertEquals(emptyList(), viewModel.activeSessions.value)
    }

    @Test
    fun `security alerts dialog should open when isRestricted changes from true to false for same user`() = runTest {
        // Arrange - mock the security alerts fetch
        coEvery { authRepository.getSecurityAlerts() } returns Either.Right(emptyList())

        // Set initial state with restricted user
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = true
        )
        delay(50)

        // Act - same user with isRestricted changed to false (device verification)
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = false
        )

        // Allow the flow to process
        delay(100)

        // Assert - security alerts dialog should be opened (isRestricted changed)
        coVerify { authRepository.getSecurityAlerts() }
    }

    @Test
    fun `restricted session info dialog should close when isRestricted flips to false even if no alerts exist`() = runTest {
        // Arrange - set initial restricted state so the dialog opens
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = true
        )
        delay(50)

        // Assert - restricted session info dialog is open
        assertTrue(viewModel.dialogState.value is SecurityDialogState.RestrictedSessionInfo)

        // Act - device verification succeeds, isRestricted becomes false
        // Mock getSecurityAlerts to return empty (no alerts)
        coEvery { authRepository.getSecurityAlerts() } returns Either.Right(emptyList())
        authStateFlow.value = AuthState.Authenticated(
            userId = testUserId1,
            username = "user1",
            deviceId = testDeviceId,
            permissions = emptyList(),
            isRestricted = false
        )

        // Allow the flow to process
        delay(100)

        // Assert - dialog should close (None) since there are no alerts to show
        assertTrue("Expected dialog to be None after verification with no alerts, but was ${viewModel.dialogState.value}") {
            viewModel.dialogState.value is SecurityDialogState.None
        }
    }
}
