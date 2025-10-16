package eu.torvian.chatbot.app.repository.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.events.AccountSwitchedEvent
import eu.torvian.chatbot.app.domain.events.AppEvent
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.api.AuthApi
import eu.torvian.chatbot.app.service.api.UserApi
import eu.torvian.chatbot.app.service.auth.TokenStorage
import eu.torvian.chatbot.app.service.auth.TokenStorageError
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import io.mockk.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.*
import eu.torvian.chatbot.app.service.auth.AccountData

/**
 * Unit tests for DefaultAuthRepository account management functionality.
 *
 * Tests cover:
 * - Getting available accounts
 * - Switching between accounts
 * - Removing accounts
 * - Updated checkInitialAuthState behavior
 * - Updated logout behavior
 */
class DefaultAuthRepositoryAccountManagementTest {

    private lateinit var authApi: AuthApi
    private lateinit var userApi: UserApi
    private lateinit var tokenStorage: TokenStorage
    private lateinit var eventBus: EventBus
    private lateinit var repository: DefaultAuthRepository

    private val testUser1 = User(
        id = 1L,
        username = "user1",
        email = "user1@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Clock.System.now(),
        lastLogin = Clock.System.now()
    )

    private val testUser2 = User(
        id = 2L,
        username = "user2",
        email = "user2@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Clock.System.now(),
        lastLogin = Clock.System.now()
    )

    private val testPermissions = listOf(
        Permission(1L, "read", "conversations"),
        Permission(2L, "write", "conversations")
    )

    @BeforeTest
    fun setup() {
        authApi = mockk()
        userApi = mockk()
        tokenStorage = mockk()
        eventBus = mockk()

        // Mock eventBus.events with a proper flow of AppEvent type
        val eventsFlow = MutableSharedFlow<AppEvent>()
        every { eventBus.events } returns eventsFlow

        // Mock eventBus.emitEvent to do nothing
        coEvery { eventBus.emitEvent(any()) } returns Unit

        repository = DefaultAuthRepository(authApi, userApi, tokenStorage, eventBus)
    }

    @AfterTest
    fun tearDown() {
        clearMocks(authApi, userApi, tokenStorage, eventBus)
    }

    // ===== switchAccount Tests =====

    @Test
    fun `switchAccount should successfully switch to existing account`() = runTest {
        // Arrange
        coEvery { tokenStorage.switchAccount(2L) } returns Unit.right()
        coEvery { tokenStorage.getAccountData(2L) } returns AccountData(testUser2, testPermissions, Clock.System.now()).right()
        coEvery { tokenStorage.listStoredAccounts() } returns listOf(
            AccountData(testUser1, testPermissions, Clock.System.now()),
            AccountData(testUser2, testPermissions, Clock.System.now())
        ).right()

        // Act
        val result = repository.switchAccount(2L)

        // Assert
        assertTrue(result.isRight())
        val authState = repository.authState.value
        assertTrue(authState is AuthState.Authenticated)
        assertEquals(2L, authState.userId)
        assertEquals("user2", authState.username)

        // Verify event was emitted
        coVerify { eventBus.emitEvent(ofType<AccountSwitchedEvent>()) }
    }

    @Test
    fun `switchAccount should emit event with previous user ID`() = runTest {
        // Arrange - set up initial authenticated state
        coEvery { tokenStorage.switchAccount(1L) } returns Unit.right()
        coEvery { tokenStorage.getAccountData(1L) } returns AccountData(testUser1, testPermissions, Clock.System.now()).right()
        coEvery { tokenStorage.listStoredAccounts() } returns listOf(
            AccountData(testUser1, testPermissions, Clock.System.now())
        ).right()
        repository.switchAccount(1L)

        // Now switch to user 2
        coEvery { tokenStorage.switchAccount(2L) } returns Unit.right()
        coEvery { tokenStorage.getAccountData(2L) } returns AccountData(testUser2, testPermissions, Clock.System.now()).right()
        coEvery { tokenStorage.listStoredAccounts() } returns listOf(
            AccountData(testUser1, testPermissions, Clock.System.now()),
            AccountData(testUser2, testPermissions, Clock.System.now())
        ).right()

        // Act
        val result = repository.switchAccount(2L)

        // Assert
        assertTrue(result.isRight())

        // Capture all emitted events
        val capturedEvents = mutableListOf<AccountSwitchedEvent>()
        coVerify(exactly = 2) { eventBus.emitEvent(capture(capturedEvents)) }
        assertEquals(2, capturedEvents.size)
        assertEquals(1L, capturedEvents[1].previousUserId)
        assertEquals(2L, capturedEvents[1].newUserId)
    }

    @Test
    fun `switchAccount should fail when account does not exist`() = runTest {
        // Arrange
        coEvery { tokenStorage.getAccountData(999L)} returns TokenStorageError.NotFound("Account not found").left()

        // Act
        val result = repository.switchAccount(999L)

        // Assert
        assertTrue(result.isLeft())
        coVerify { tokenStorage.getAccountData(999L) }
    }

    // ===== removeAccount Tests =====

    @Test
    fun `removeAccount should successfully remove non-active account`() = runTest {
        // Arrange - set up user 1 as active
        coEvery { tokenStorage.switchAccount(1L) } returns Unit.right()
        coEvery { tokenStorage.getAccountData(1L) } returns AccountData(testUser1, testPermissions, Clock.System.now()).right()
        coEvery { tokenStorage.listStoredAccounts() } returns listOf(
            AccountData(testUser1, testPermissions, Clock.System.now()),
            AccountData(testUser2, testPermissions, Clock.System.now())
        ).right()
        repository.switchAccount(1L)

        // Remove user 2 (not active)
        coEvery { tokenStorage.removeAccount(2L) } returns Unit.right()
        coEvery { tokenStorage.listStoredAccounts() } returns listOf(
            AccountData(testUser1, testPermissions, Clock.System.now())
        ).right()

        // Act
        val result = repository.removeAccount(2L)

        // Assert
        assertTrue(result.isRight())
        val authState = repository.authState.value
        assertTrue(authState is AuthState.Authenticated) // Still authenticated as user 1
        assertEquals(1L, authState.userId)
        coVerify { tokenStorage.removeAccount(2L) }
    }

    @Test
    fun `removeAccount should set state to Unauthenticated when removing active account`() = runTest {
        // Arrange - set up user 1 as active
        coEvery { tokenStorage.switchAccount(1L) } returns Unit.right()
        coEvery { tokenStorage.getAccountData(1L) } returns AccountData(testUser1, testPermissions, Clock.System.now()).right()
        coEvery { tokenStorage.listStoredAccounts() } returns listOf(
            AccountData(testUser1, testPermissions, Clock.System.now())
        ).right()
        repository.switchAccount(1L)

        // Remove user 1 (active)
        coEvery { tokenStorage.removeAccount(1L) } returns Unit.right()

        // Act
        val result = repository.removeAccount(1L)

        // Assert
        assertTrue(result.isRight())
        val authState = repository.authState.value
        assertTrue(authState is AuthState.Unauthenticated)
        coVerify { tokenStorage.removeAccount(1L) }
    }

    @Test
    fun `removeAccount should handle storage errors`() = runTest {
        // Arrange
        coEvery { tokenStorage.removeAccount(1L) } returns TokenStorageError.IOError("Storage error").left()

        // Act
        val result = repository.removeAccount(1L)

        // Assert
        assertTrue(result.isLeft())
    }

    // ===== checkInitialAuthState Tests =====

    @Test
    fun `checkInitialAuthState should set Authenticated when active account exists`() = runTest {
        // Arrange
        coEvery { tokenStorage.getAccountData() } returns AccountData(testUser1, testPermissions, Clock.System.now()).right()
        coEvery { tokenStorage.listStoredAccounts() } returns listOf(
            AccountData(testUser1, testPermissions, Clock.System.now())
        ).right()

        // Act
        repository.checkInitialAuthState()

        // Assert
        val authState = repository.authState.value
        assertTrue(authState is AuthState.Authenticated)
        assertEquals(1L, authState.userId)
        assertEquals("user1", authState.username)
    }

    @Test
    fun `checkInitialAuthState should set Unauthenticated when user data cannot be loaded`() = runTest {
        // Arrange
        coEvery { tokenStorage.getAccountData() } returns TokenStorageError.NotFound("User data not found").left()

        // Act
        repository.checkInitialAuthState()

        // Assert
        assertTrue(repository.authState.value is AuthState.Unauthenticated)
    }

    // ===== logout Tests =====

    @Test
    fun `logout should remove active account when authenticated`() = runTest {
        // Arrange - set up user 1 as active
        coEvery { tokenStorage.switchAccount(1L) } returns Unit.right()
        coEvery { tokenStorage.getAccountData(1L) } returns AccountData(testUser1, testPermissions, Clock.System.now()).right()
        coEvery { tokenStorage.listStoredAccounts() } returns listOf(
            AccountData(testUser1, testPermissions, Clock.System.now())
        ).right()
        repository.switchAccount(1L)
        assertTrue(repository.authState.value is AuthState.Authenticated)

        coEvery { tokenStorage.clearAuthData() } returns Unit.right()
        coEvery { authApi.logout() } returns Unit.right()

        // Act
        val result = repository.logout()

        // Assert
        assertTrue(result.isRight())
        assertTrue(repository.authState.value is AuthState.Unauthenticated)
        coVerify { tokenStorage.clearAuthData() }
        coVerify { authApi.logout() }
    }

    @Test
    fun `logout should still set Unauthenticated even if storage fails`() = runTest {
        // Arrange - set up user 1 as active
        coEvery { tokenStorage.switchAccount(1L) } returns Unit.right()
        coEvery { tokenStorage.getAccountData(1L) } returns AccountData(testUser1, testPermissions, Clock.System.now()).right()
        coEvery { tokenStorage.listStoredAccounts() } returns listOf(
            AccountData(testUser1, testPermissions, Clock.System.now())
        ).right()
        repository.switchAccount(1L)
        assertTrue(repository.authState.value is AuthState.Authenticated)

        coEvery { authApi.logout() } returns Unit.right()
        coEvery { tokenStorage.clearAuthData() } returns TokenStorageError.IOError("Storage error").left()
        coEvery { tokenStorage.listStoredAccounts() } returns emptyList<AccountData>().right()

        // Act
        val result = repository.logout()

        // Assert
        assertTrue(result.isRight()) // API logout succeeded
        assertTrue(repository.authState.value is AuthState.Unauthenticated)
    }
}
