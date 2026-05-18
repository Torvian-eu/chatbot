package eu.torvian.chatbot.app.viewmodel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that serves as the single source of truth for authentication state
 * and session lifecycle operations in the application.
 *
 * This ViewModel is responsible for:
 * - Exposing the current authentication state from the repository
 * - Performing the initial authentication state check on app startup
 * - Managing session lifecycle operations (logout, logoutAll)
 * - Providing a unified entry point for auth-aware navigation decisions
 *
 * @param authRepository Repository for authentication operations and state management.
 * @param notificationService Service for handling and notifying about logout errors.
 * @param normalScope Coroutine scope for normal operations.
 */
class SessionViewModel(
    private val authRepository: AuthRepository,
    private val notificationService: NotificationService,
    normalScope: CoroutineScope
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<SessionViewModel>()
    }

    /**
     * The current authentication state, delegated directly to the repository's StateFlow.
     * This allows the UI to reactively observe authentication changes.
     */
    val authState: StateFlow<AuthState> = authRepository.authState

    /**
     * Checks the initial authentication state on app startup.
     * This method should be called once when the application launches to validate
     * existing tokens with the server and establish the initial auth state.
     */
    fun checkInitialAuthState() {
        viewModelScope.launch {
            authRepository.checkInitialAuthState()
        }
    }

    /**
     * Logs out the current user from this session.
     *
     * On failure, notifies the user via [NotificationService].
     */
    fun logout() {
        viewModelScope.launch {
            authRepository.logout().onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Logout failed"
                )
            }
        }
    }

    /**
     * Logs the current user out from all sessions.
     *
     * On failure, notifies the user via [NotificationService].
     */
    fun logoutAll() {
        viewModelScope.launch {
            authRepository.logoutAll().onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Logout all sessions failed"
                )
            }
        }
    }

    /**
     * Sets the authentication state to Unauthenticated.
     *
     * This triggers navigation to the AuthenticationFlow from the MainApplicationFlow,
     * for example when the user wants to add another account.
     */
    fun setUnauthenticated() {
        viewModelScope.launch {
            authRepository.setUnauthenticated()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // normalScope will be cancelled when the ViewModel is cleared
    }
}
