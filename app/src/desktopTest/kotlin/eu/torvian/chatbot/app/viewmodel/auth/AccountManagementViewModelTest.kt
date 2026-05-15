package eu.torvian.chatbot.app.viewmodel.auth

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.user.Permission
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
import kotlin.time.Clock

/**
 * Unit tests for AccountManagementViewModel.
 *
 * Tests cover:
 * - Account switching
 * - Account removal
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountManagementViewModelTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var notificationService: NotificationService
    private lateinit var testScope: CoroutineScope
    private lateinit var viewModel: AccountManagementViewModel

    private val testUser1 = User(
        id = 1L,
        username = "user1",
        email = "user1@example.com",
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
        authRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)
        testScope = CoroutineScope(UnconfinedTestDispatcher())

        // Set up auth state flow
        every { authRepository.authState } returns MutableStateFlow<AuthState>(AuthState.Unauthenticated)
        every { authRepository.availableAccounts } returns MutableStateFlow(emptyList())

        viewModel = AccountManagementViewModel(
            authRepository = authRepository,
            notificationService = notificationService,
            normalScope = testScope
        )
    }

    @AfterTest
    fun tearDown() {
        clearMocks(authRepository, notificationService)
    }

    // ===== Account Switching Tests =====

    @Test
    fun `switchAccount should call repository and close dialog on success`() = runTest {
        // Arrange
        coEvery { authRepository.switchAccount(2L) } returns Unit.right()

        // Act
        viewModel.switchAccount(2L)

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.switchAccount(2L) }
        assertTrue(viewModel.dialogState.value is AccountDialogState.None)
    }

    @Test
    fun `switchAccount should show error notification on failure`() = runTest {
        // Arrange
        val error = mockk<eu.torvian.chatbot.app.repository.RepositoryError>(relaxed = true)
        coEvery { authRepository.switchAccount(999L) } returns error.left()

        // Act
        viewModel.switchAccount(999L)

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.switchAccount(999L) }
        coVerify { notificationService.repositoryError(error, "Failed to switch account") }
    }

    @Test
    fun `switchAccount should set loading state during operation`() = runTest {
        // Arrange
        coEvery { authRepository.switchAccount(2L) } returns Unit.right()

        // Act
        viewModel.switchAccount(2L)

        // Allow coroutine to complete
        delay(100)

        // Assert - check loading state is false after operation completes
        assertFalse(viewModel.accountSwitchInProgress.value)
    }


    // ===== Account Removal Tests =====

    @Test
    fun `removeAccount should call repository and close dialog on success`() = runTest {
        // Arrange
        coEvery { authRepository.removeAccount(2L) } returns Unit.right()

        // Act
        viewModel.removeAccount(2L)

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.removeAccount(2L) }
        assertTrue(viewModel.dialogState.value is AccountDialogState.None)
    }

    @Test
    fun `removeAccount should show error notification on failure`() = runTest {
        // Arrange
        val error = mockk<eu.torvian.chatbot.app.repository.RepositoryError>(relaxed = true)
        coEvery { authRepository.removeAccount(1L) } returns error.left()

        // Act
        viewModel.removeAccount(1L)

        // Allow coroutine to complete
        delay(100)

        // Assert
        coVerify { authRepository.removeAccount(1L) }
        coVerify { notificationService.repositoryError(error, "Failed to remove account") }
    }

    // ===== Dialog State Management Tests =====

    @Test
    fun `openAccountSwitcher should set dialog state`() {
        // Act
        viewModel.openAccountSwitcher()

        // Assert
        assertTrue(viewModel.dialogState.value is AccountDialogState.SwitchAccount)
    }

    @Test
    fun `openRemoveAccountConfirmation should set dialog state with account`() {
        // Arrange
        val account = AccountData(testUser1, testPermissions, Clock.System.now())

        // Act
        viewModel.openRemoveAccountConfirmation(account)

        // Assert
        val state = viewModel.dialogState.value
        assertTrue(state is AccountDialogState.RemoveAccountConfirmation)
        assertEquals(account, state.account)
    }

    @Test
    fun `closeDialog should set dialog state to None`() {
        // Arrange
        viewModel.openAccountSwitcher()
        assertTrue(viewModel.dialogState.value is AccountDialogState.SwitchAccount)

        // Act
        viewModel.closeDialog()

        // Assert
        assertTrue(viewModel.dialogState.value is AccountDialogState.None)
    }
}
