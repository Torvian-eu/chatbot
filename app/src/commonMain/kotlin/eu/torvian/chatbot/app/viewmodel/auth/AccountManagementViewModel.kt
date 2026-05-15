package eu.torvian.chatbot.app.viewmodel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing account switching and removal operations.
 *
 * This ViewModel handles:
 * - Account switching between stored accounts
 * - Account removal from local storage
 * - Switch account dialog state
 *
 * @param authRepository Repository for authentication operations.
 * @param notificationService Service for handling and notifying about errors.
 * @param normalScope Coroutine scope for normal operations.
 */
class AccountManagementViewModel(
    private val authRepository: AuthRepository,
    private val notificationService: NotificationService,
    private val normalScope: CoroutineScope
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<AccountManagementViewModel>()
    }

    // --- Authentication State (delegated to repository) ---

    /**
     * The current authentication state from the repository.
     */
    val authState: StateFlow<AuthState> = authRepository.authState

    // --- Account Management State ---

    /**
     * List of all stored user accounts available for switching.
     */
    val availableAccounts: StateFlow<List<AccountData>> = authRepository.availableAccounts

    /**
     * Indicates whether an account switch operation is currently in progress.
     * Used to show loading states in the UI during account switching.
     */
    private val _accountSwitchInProgress = MutableStateFlow(false)
    val accountSwitchInProgress: StateFlow<Boolean> = _accountSwitchInProgress.asStateFlow()

    // --- Dialog State Management ---

    private val _dialogState = MutableStateFlow<AccountDialogState>(AccountDialogState.None)
    val dialogState: StateFlow<AccountDialogState> = _dialogState.asStateFlow()

    // --- Account Management Operations ---

    /**
     * Switches to a different user account.
     *
     * This method changes the active account without requiring the user to log out and log back in.
     * The authentication state will be automatically updated to reflect the new account, and the
     * available accounts list will be refreshed to update the "last used" timestamp.
     *
     * @param userId The ID of the account to switch to.
     */
    fun switchAccount(userId: Long) {
        viewModelScope.launch {
            _accountSwitchInProgress.value = true
            // Clear account-scoped security state to avoid stale data from the previous account.
            // Note: Security state is managed by SecurityAuditViewModel

            authRepository.switchAccount(userId)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to switch account"
                    )
                }
                .onRight {
                    // Close dialog on successful switch
                    _dialogState.value = AccountDialogState.None
                }

            _accountSwitchInProgress.value = false
        }
    }

    /**
     * Removes an account from local storage.
     *
     * This permanently deletes the specified account's stored authentication data.
     * If the removed account is the currently active account, the user will be logged out
     * and the authentication state will become [AuthState.Unauthenticated].
     *
     * @param userId The ID of the account to remove.
     */
    fun removeAccount(userId: Long) {
        viewModelScope.launch {
            authRepository.removeAccount(userId)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to remove account"
                    )
                }
                .onRight {
                    // Close dialog on successful removal
                    _dialogState.value = AccountDialogState.None
                }
        }
    }

     // --- Dialog State Management ---

    /**
     * Opens the account switcher dialog.
     */
    fun openAccountSwitcher() {
        _dialogState.value = AccountDialogState.SwitchAccount
    }

    /**
     * Opens the remove account confirmation dialog.
     *
     * @param account The account to be removed.
     */
    fun openRemoveAccountConfirmation(account: AccountData) {
        _dialogState.value = AccountDialogState.RemoveAccountConfirmation(account)
    }

    /**
     * Closes any open authentication dialog.
     */
    fun closeDialog() {
        _dialogState.value = AccountDialogState.None
    }

    override fun onCleared() {
        super.onCleared()
        normalScope.cancel()
    }
}
