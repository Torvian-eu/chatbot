package eu.torvian.chatbot.app.viewmodel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for managing authentication UI state and operations.
 *
 * This ViewModel handles:
 * - Authentication state management (delegated to AuthRepository)
 * - Login and registration form state
 * - Form validation and error handling
 * - User authentication operations, including logout and logout-all flows
 * - Multi-account management (listing, switching, removing accounts)
 * - Security alerts management (IP-based login alerts)
 *
 * @param authRepository Repository for authentication operations
 * @param notificationService Service for handling and notifying about errors
 * @param normalScope Coroutine scope for normal operations
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val notificationService: NotificationService,
    private val normalScope: CoroutineScope
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<AuthViewModel>()
    }

    // --- Authentication State (delegated to repository) ---

    /**
     * The current authentication state from the repository.
     */
    val authState: StateFlow<AuthState> = authRepository.authState

    /**
     * Whether the current session is restricted (created from an unacknowledged IP).
     * Derived from the current auth state.
     */
    val isCurrentSessionRestricted: StateFlow<Boolean>
        get() {
            val currentState = authRepository.authState.value
            return if (currentState is AuthState.Authenticated) {
                MutableStateFlow(currentState.isRestricted)
            } else {
                MutableStateFlow(false)
            }
        }

    // --- Form State Management ---

    private val _loginFormState = MutableStateFlow(LoginFormState())
    val loginFormState: StateFlow<LoginFormState> = _loginFormState.asStateFlow()

    private val _registerFormState = MutableStateFlow(RegisterFormState())
    val registerFormState: StateFlow<RegisterFormState> = _registerFormState.asStateFlow()

    private val _passwordChangeFormState = MutableStateFlow(PasswordChangeFormState())
    val passwordChangeFormState: StateFlow<PasswordChangeFormState> = _passwordChangeFormState.asStateFlow()

    // --- Account Management State ---

    /**
     * List of all stored user accounts available for switching.
     */
    val availableAccounts: StateFlow<List<AccountData>> = authRepository.availableAccounts

    /**
     * The active sessions currently fetched from the server for the security dialog.
     */
    val activeSessions = MutableStateFlow<List<UserSessionInfo>>(emptyList())

    /**
     * Unacknowledged security alerts for the current user.
     * These represent login attempts from unrecognized IP addresses.
     */
    val securityAlerts = MutableStateFlow<List<UserSecurityAlert>>(emptyList())

    /**
     * Indicates whether an account switch operation is currently in progress.
     * Used to show loading states in the UI during account switching.
     */
    private val _accountSwitchInProgress = MutableStateFlow(false)
    val accountSwitchInProgress: StateFlow<Boolean> = _accountSwitchInProgress.asStateFlow()

    // --- Dialog State Management ---

    private val _dialogState = MutableStateFlow<AuthDialogState>(AuthDialogState.None)
    val dialogState: StateFlow<AuthDialogState> = _dialogState.asStateFlow()

    // --- Authentication Operations ---

    /**
     * Attempts to log in the user using the current login form state.
     */
    fun login() {
        viewModelScope.launch {
            val currentForm = _loginFormState.value
            val username = currentForm.username
            val password = currentForm.password

            logger.info("Attempting login for user: $username")

            // Validate form before submission
            val usernameError = AuthFormValidation.validateUsername(username)
            val passwordError = if (password.isBlank()) "Password is required" else null

            if (usernameError != null || passwordError != null) {
                _loginFormState.update { currentState ->
                    currentState.copy(
                        usernameError = usernameError,
                        passwordError = passwordError,
                        generalError = null
                    )
                }
                return@launch
            }

            // Clear errors and set loading state
            _loginFormState.update { currentState ->
                currentState.copy(
                    isLoading = true,
                    usernameError = null,
                    passwordError = null,
                    generalError = null
                )
            }

            // Perform login
            val result = authRepository.login(username, password)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Login failed for user $username: ${error.message}")
                    _loginFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = mapLoginError(error)
                        )
                    }
                },
                ifRight = {
                    logger.info("Login successful for user: $username")
                    _loginFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = null
                        )
                    }
                    // Clear form on successful login
                    clearLoginForm()
                    // Close the add account dialog if it's open
                    if (_dialogState.value is AuthDialogState.AddAccount) {
                        _dialogState.value = AuthDialogState.None
                    }
                    // Fetch security alerts after successful login
                    fetchSecurityAlerts()
                }
            )
        }
    }

    /**
     * Attempts to register a new user using the current registration form state.
     */
    fun register() {
        viewModelScope.launch {
            val currentForm = _registerFormState.value
            val username = currentForm.username
            val email = currentForm.email
            val password = currentForm.password
            val confirmPassword = currentForm.confirmPassword

            logger.info("Attempting registration for user: $username")

            // Validate all form fields
            val usernameError = AuthFormValidation.validateUsername(username)
            val emailError = AuthFormValidation.validateEmail(email)
            val passwordError = AuthFormValidation.validatePassword(password)
            val confirmPasswordError = AuthFormValidation.validateConfirmPassword(password, confirmPassword)

            if (usernameError != null || emailError != null || passwordError != null || confirmPasswordError != null) {
                _registerFormState.update { currentState ->
                    currentState.copy(
                        usernameError = usernameError,
                        emailError = emailError,
                        passwordError = passwordError,
                        confirmPasswordError = confirmPasswordError,
                        generalError = null
                    )
                }
                return@launch
            }

            // Clear errors and set loading state
            _registerFormState.update { currentState ->
                currentState.copy(
                    isLoading = true,
                    usernameError = null,
                    emailError = null,
                    passwordError = null,
                    confirmPasswordError = null,
                    generalError = null,
                    registrationSuccessEvent = false
                )
            }

            // Perform registration
            val emailToSend = email.ifBlank { null }
            val result = authRepository.register(username, password, emailToSend)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Registration failed for user $username: ${error.message}")
                    _registerFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = mapRegistrationError(error)
                        )
                    }
                },
                ifRight = { user ->
                    logger.info("Registration successful for user: ${user.username}")
                    _registerFormState.value = RegisterFormState(
                        registrationSuccessEvent = true
                    )
                }
            )
        }
    }

    /**
     * Logs out the current user.
     */
    fun logout() {
        viewModelScope.launch {
            authRepository.logout().onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Logout failed"
                )
            }.onRight {
                activeSessions.value = emptyList()
                securityAlerts.value = emptyList()
                clearAllForms()
            }
        }
    }

    /**
     * Logs the current user out from all sessions.
     *
     * The repository is responsible for clearing local authentication state, and the view model
     * only reacts to the outcome by surfacing errors or resetting local forms.
     */
    fun logoutAll() {
        viewModelScope.launch {
            authRepository.logoutAll().onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Logout all sessions failed"
                )
            }.onRight {
                activeSessions.value = emptyList()
                securityAlerts.value = emptyList()
                clearAllForms()
            }
        }
    }

    /**
     * Fetches the authenticated user's current active sessions from the repository.
     */
    fun refreshSessions() {
        viewModelScope.launch {
            authRepository.getActiveSessions()
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load active sessions"
                    )
                }
                .onRight { sessions ->
                    activeSessions.value = sessions
                }
        }
    }

    /**
     * Fetches unacknowledged security alerts for the current user.
     * Called after successful login or when the user navigates to the security alerts view.
     */
    fun fetchSecurityAlerts() {
        viewModelScope.launch {
            authRepository.getSecurityAlerts()
                .onLeft { error ->
                    logger.warn("Failed to fetch security alerts: ${error.message}")
                    // Don't show error notification - security alerts are not critical
                    securityAlerts.value = emptyList()
                }
                .onRight { alerts ->
                    securityAlerts.value = alerts
                    logger.info("Fetched ${alerts.size} security alerts")
                }
        }
    }

    /**
     * Acknowledges all pending security alerts for the current user.
     * This marks all unacknowledged IP addresses as trusted.
     *
     * Note: This operation is not available for restricted sessions.
     */
    fun acknowledgeSecurityAlerts() {
        viewModelScope.launch {
            authRepository.acknowledgeSecurityAlerts()
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to acknowledge security alerts"
                    )
                }
                .onRight {
                    securityAlerts.value = emptyList()
                    logger.info("Successfully acknowledged all security alerts")
                }
        }
    }

    /**
     * Revokes one of the authenticated user's active sessions and refreshes the list afterwards.
     *
     * @param sessionId The server session identifier to revoke.
     */
    fun revokeSession(sessionId: Long) {
        viewModelScope.launch {
            authRepository.revokeSession(sessionId)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to revoke session"
                    )
                }
                .onRight {
                    refreshSessions()
                }
        }
    }

    /**
     * Changes the password for the currently authenticated user.
     * Used when the user is forced to change their password on first login.
     */
    fun changePassword(userId: Long) {
        viewModelScope.launch {
            val currentForm = _passwordChangeFormState.value
            val newPassword = currentForm.newPassword
            val confirmPassword = currentForm.confirmPassword

            logger.info("Attempting password change for user: $userId")

            // Validate form before submission
            val newPasswordError = AuthFormValidation.validatePassword(newPassword)
            val confirmPasswordError = AuthFormValidation.validateConfirmPassword(newPassword, confirmPassword)

            if (newPasswordError != null || confirmPasswordError != null) {
                _passwordChangeFormState.update { currentState ->
                    currentState.copy(
                        newPasswordError = newPasswordError,
                        confirmPasswordError = confirmPasswordError,
                        generalError = null
                    )
                }
                return@launch
            }

            // Clear errors and set loading state
            _passwordChangeFormState.update { currentState ->
                currentState.copy(
                    isLoading = true,
                    newPasswordError = null,
                    confirmPasswordError = null,
                    generalError = null
                )
            }

            // Perform password change
            val result = authRepository.changePassword(userId, newPassword)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Password change failed for user $userId: ${error.message}")
                    _passwordChangeFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = mapPasswordChangeError(error)
                        )
                    }
                },
                ifRight = {
                    logger.info("Password change successful for user: $userId")
                    _passwordChangeFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = null,
                            passwordChangeSuccessEvent = true
                        )
                    }
                }
            )
        }
    }

    /**
     * Checks the initial authentication state on app startup.
     */
    suspend fun checkInitialAuthState() {
        authRepository.checkInitialAuthState()
        // Fetch security alerts if authenticated after startup
        if (authRepository.authState.value is AuthState.Authenticated) {
            fetchSecurityAlerts()
        }
    }

    // --- Account Management Operations ---

    /**
     * Switches to a different user account.
     *
     * This method changes the active account without requiring the user to log out and log back in.
     * The authentication state will be automatically updated to reflect the new account, and the
     * available accounts list will be refreshed to update the "last used" timestamp.
     *
     * @param userId The ID of the account to switch to
     */
    fun switchAccount(userId: Long) {
        viewModelScope.launch {
            _accountSwitchInProgress.value = true

            authRepository.switchAccount(userId)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to switch account"
                    )
                }
                .onRight {
                    // Close dialog on successful switch
                    _dialogState.value = AuthDialogState.None
                    // Fetch security alerts for the new account
                    fetchSecurityAlerts()
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
     * @param userId The ID of the account to remove
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
                    _dialogState.value = AuthDialogState.None
                }
        }
    }

    // --- Dialog State Management ---

    /**
     * Opens the account switcher dialog.
     */
    fun openAccountSwitcher() {
        _dialogState.value = AuthDialogState.SwitchAccount
    }

    /**
     * Opens the active sessions security dialog.
     */
    fun openActiveSessions() {
        _dialogState.value = AuthDialogState.ActiveSessions
    }

    /**
     * Opens the remove account confirmation dialog.
     *
     * @param account The account to be removed
     */
    fun openRemoveAccountConfirmation(account: AccountData) {
        _dialogState.value = AuthDialogState.RemoveAccountConfirmation(account)
    }

    /**
     * Closes any open authentication dialog.
     */
    fun closeDialog() {
        _dialogState.value = AuthDialogState.None
    }

    // --- Form State Updates ---

    /**
     * Updates the login form state with optional named parameters for each field.
     * Only the provided fields will be updated; others remain unchanged.
     * Field-specific errors are cleared if the corresponding field is updated.
     */
    fun updateLoginForm(username: String? = null, password: String? = null) {
        _loginFormState.update { currentState ->
            currentState.copy(
                username = username ?: currentState.username,
                password = password ?: currentState.password,
                // Clear field-specific errors when user types
                usernameError = if (username != null && username != currentState.username) null else currentState.usernameError,
                passwordError = if (password != null && password != currentState.password) null else currentState.passwordError
            )
        }
    }

    /**
     * Updates the registration form state with optional named parameters for each field.
     * Only the provided fields will be updated; others remain unchanged.
     * Field-specific errors are cleared if the corresponding field is updated.
     */
    fun updateRegisterForm(
        username: String? = null,
        email: String? = null,
        password: String? = null,
        confirmPassword: String? = null
    ) {
        _registerFormState.update { currentState ->
            currentState.copy(
                username = username ?: currentState.username,
                email = email ?: currentState.email,
                password = password ?: currentState.password,
                confirmPassword = confirmPassword ?: currentState.confirmPassword,
                // Clear field-specific errors when user types
                usernameError = if (username != null && username != currentState.username) null else currentState.usernameError,
                emailError = if (email != null && email != currentState.email) null else currentState.emailError,
                passwordError = if (password != null && password != currentState.password) null else currentState.passwordError,
                confirmPasswordError = if (confirmPassword != null && confirmPassword != currentState.confirmPassword) null else currentState.confirmPasswordError
            )
        }
    }

    /**
     * Updates the password change form state with optional named parameters for each field.
     * Only the provided fields will be updated; others remain unchanged.
     * Field-specific errors are cleared if the corresponding field is updated.
     */
    fun updatePasswordChangeForm(
        newPassword: String? = null,
        confirmPassword: String? = null
    ) {
        _passwordChangeFormState.update { currentState ->
            currentState.copy(
                newPassword = newPassword ?: currentState.newPassword,
                confirmPassword = confirmPassword ?: currentState.confirmPassword,
                // Clear field-specific errors when user types
                newPasswordError = if (newPassword != null && newPassword != currentState.newPassword) null else currentState.newPasswordError,
                confirmPasswordError = if (confirmPassword != null && confirmPassword != currentState.confirmPassword) null else currentState.confirmPasswordError
            )
        }
    }

    /**
     * Resets the registration form state, typically called by the UI after
     * successful navigation away from the registration success screen.
     */
    fun acknowledgeRegistrationSuccess() {
        _registerFormState.value = RegisterFormState()
    }

    /**
     * Clears the login form state.
     */
    fun clearLoginForm() {
        _loginFormState.value = LoginFormState()
    }

    /**
     * Clears the registration form state.
     */
    fun clearRegisterForm() {
        _registerFormState.value = RegisterFormState()
    }

    /**
     * Clears the password change form state.
     */
    fun clearPasswordChangeForm() {
        _passwordChangeFormState.value = PasswordChangeFormState()
    }

    /**
     * Clears all form states.
     */
    fun clearAllForms() {
        clearLoginForm()
        clearRegisterForm()
        clearPasswordChangeForm()
    }

    // --- Error Mapping ---

    private fun mapLoginError(error: RepositoryError): String {
        return when (error) {
            is RepositoryError.DataFetchError -> when {
                error.message.contains("Invalid credentials", ignoreCase = true) ->
                    "Invalid username or password"

                error.message.contains("User not found", ignoreCase = true) ->
                    "Invalid username or password"

                error.message.contains("Account locked", ignoreCase = true) ->
                    "Account is temporarily locked. Please try again later."

                error.message.contains("verification-required", ignoreCase = true) ->
                    "New login detected. Please check your email to verify your identity."

                else -> "Login failed. Please try again."
            }

            is RepositoryError.OtherError ->
                "An unexpected error occurred. Please try again."
        }
    }

    private fun mapRegistrationError(error: RepositoryError): String {
        return when (error) {
            is RepositoryError.DataFetchError -> when {
                error.message.contains("Username already exists", ignoreCase = true) ->
                    "Username is already taken. Please choose a different one."

                error.message.contains("Email already exists", ignoreCase = true) ->
                    "Email is already registered. Please use a different email or try logging in."

                else -> "Registration failed. Please try again."
            }

            is RepositoryError.OtherError ->
                "An unexpected error occurred. Please try again."
        }
    }

    private fun mapPasswordChangeError(error: RepositoryError): String {
        return when (error) {
            is RepositoryError.DataFetchError -> when {
                error.message.contains("Weak password", ignoreCase = true) ->
                    "New password is too weak. Please choose a stronger password."

                error.message.contains("Password cannot be reused", ignoreCase = true) ->
                    "New password cannot be the same as the old password."

                else -> "Password change failed. Please try again."
            }

            is RepositoryError.OtherError ->
                "An unexpected error occurred. Please try again."
        }
    }

    override fun onCleared() {
        super.onCleared()
        normalScope.cancel()
    }
}
