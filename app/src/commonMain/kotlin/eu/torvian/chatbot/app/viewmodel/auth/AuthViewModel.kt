package eu.torvian.chatbot.app.viewmodel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.matches
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.service.auth.AuthValidationService
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo
import eu.torvian.chatbot.common.security.PasswordValidationConfig
import eu.torvian.chatbot.common.security.UsernameValidationConfig
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
 * @param clipboardService Service for clipboard operations
 * @param normalScope Coroutine scope for normal operations
 * @param authValidationService Service for validating authentication form fields
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val notificationService: NotificationService,
    private val clipboardService: ClipboardService,
    private val normalScope: CoroutineScope,
    private val authValidationService: AuthValidationService
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<AuthViewModel>()
    }

    // --- Authentication State (delegated to repository) ---

    /**
     * The current authentication state from the repository.
     */
    val authState: StateFlow<AuthState> = authRepository.authState

    // --- Form State Management ---

    private val _loginFormState = MutableStateFlow(LoginFormState())
    val loginFormState: StateFlow<LoginFormState> = _loginFormState.asStateFlow()

    private val _registerFormState = MutableStateFlow(RegisterFormState())
    val registerFormState: StateFlow<RegisterFormState> = _registerFormState.asStateFlow()

    private val _passwordChangeFormState = MutableStateFlow(PasswordChangeFormState())
    val passwordChangeFormState: StateFlow<PasswordChangeFormState> = _passwordChangeFormState.asStateFlow()

    /**
     * The password validation configuration from the authentication service.
     * Exposed so that UI components can dynamically render password requirement hints.
     */
    val passwordValidationConfig: PasswordValidationConfig = authValidationService.passwordValidationConfig

    /**
     * The username validation configuration from the authentication service.
     * Exposed so that UI components can dynamically render username requirement hints.
     */
    val usernameValidationConfig: UsernameValidationConfig = authValidationService.usernameValidationConfig

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
     * These represent login attempts from unrecognized devices.
     */
    val securityAlerts = MutableStateFlow<List<UserSecurityAlert>>(emptyList())

    /**
     * Trusted devices for the current user.
     * These are devices that have been trusted through first use or security alert acknowledgement.
     */
    val trustedDevices = MutableStateFlow<List<UserTrustedDeviceInfo>>(emptyList())

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
            val usernameError = authValidationService.validateUsername(username)
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
            val usernameError = authValidationService.validateUsername(username)
            val emailError = authValidationService.validateEmail(email)
            val passwordError = authValidationService.validatePassword(password)
            val confirmPasswordError = authValidationService.validateConfirmPassword(password, confirmPassword)

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
     * This marks all unacknowledged security alerts as trusted.
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
     * Fetches the authenticated user's trusted devices from the repository.
     */
    fun refreshTrustedDevices() {
        viewModelScope.launch {
            authRepository.getTrustedDevices()
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load trusted devices"
                    )
                }
                .onRight { devices ->
                    trustedDevices.value = devices
                }
        }
    }

    /**
     * Revokes one of the authenticated user's trusted devices and refreshes the list afterwards.
     *
     * @param deviceId The device identifier to revoke.
     */
    fun revokeTrustedDevice(deviceId: String) {
        viewModelScope.launch {
            authRepository.revokeTrustedDevice(deviceId)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to revoke trusted device"
                    )
                }
                .onRight {
                    refreshTrustedDevices()
                }
        }
    }

    /**
     * Changes the password for the currently authenticated user.
     * Used when the user is forced to change their password on first login.
     */
    fun changePassword() {
        viewModelScope.launch {
            val currentForm = _passwordChangeFormState.value
            val newPassword = currentForm.newPassword
            val confirmPassword = currentForm.confirmPassword

            logger.info("Attempting password change")

            // Validate form before submission - currentPassword, newPassword, and confirmPassword required
            val currentPasswordError = if (currentForm.currentPassword.isBlank()) "Current password is required" else null
            val newPasswordError = authValidationService.validatePassword(newPassword)
            val confirmPasswordError = authValidationService.validateConfirmPassword(newPassword, confirmPassword)

            if (currentPasswordError != null || newPasswordError != null || confirmPasswordError != null) {
                _passwordChangeFormState.update { currentState ->
                    currentState.copy(
                        currentPasswordError = currentPasswordError,
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
                    currentPasswordError = null,
                    newPasswordError = null,
                    confirmPasswordError = null,
                    generalError = null
                )
            }

            // Perform password change using the repository's changePassword with current password
            val result = authRepository.changePassword(currentForm.currentPassword, newPassword)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Password change failed: ${error.message}")
                    _passwordChangeFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = mapPasswordChangeError(error)
                        )
                    }
                },
                ifRight = {
                    logger.info("Password change successful")
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
     * Completes a server-required password change for the currently authenticated user.
     *
     * This method is used when the user is forced to change their password
     * (requiresPasswordChange = true). Unlike normal password change, it does not
     * require the current password.
     */
    fun completeRequiredPasswordChange() {
        viewModelScope.launch {
            val currentForm = _passwordChangeFormState.value
            val newPassword = currentForm.newPassword
            val confirmPassword = currentForm.confirmPassword

            logger.info("Attempting required password change")

            // Validate form before submission - only newPassword and confirmPassword required
            val newPasswordError = authValidationService.validatePassword(newPassword)
            val confirmPasswordError = authValidationService.validateConfirmPassword(newPassword, confirmPassword)

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

            // Perform required password change using the repository
            val result = authRepository.completeRequiredPasswordChange(newPassword)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Required password change failed: ${error.message}")
                    _passwordChangeFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = mapPasswordChangeError(error)
                        )
                    }
                },
                ifRight = {
                    logger.info("Required password change successful")
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
     * Opens the trusted devices dialog.
     */
    fun openTrustedDevices() {
        _dialogState.value = AuthDialogState.TrustedDevices
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
     * Opens the change password dialog.
     */
    fun openChangePasswordDialog() {
        _dialogState.value = AuthDialogState.ChangePassword
    }

    /**
     * Closes any open authentication dialog.
     */
    fun closeDialog() {
        _dialogState.value = AuthDialogState.None
    }

    // --- Clipboard Operations ---

    /**
     * Copies the given text to the system clipboard and shows a success notification.
     *
     * @param text The text to copy to the clipboard.
     */
    fun copyToClipboard(text: String) {
        viewModelScope.launch {
            try {
                clipboardService.copyToClipboard(text)
                notificationService.genericSuccess("Copied to clipboard")
            } catch (e: Exception) {
                logger.warn("Failed to copy to clipboard: ${e.message}")
                notificationService.genericError(
                    shortMessage = "Failed to copy to clipboard",
                    detailedMessage = e.message
                )
            }
        }
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
        currentPassword: String? = null,
        newPassword: String? = null,
        confirmPassword: String? = null
    ) {
        _passwordChangeFormState.update { currentState ->
            currentState.copy(
                currentPassword = currentPassword ?: currentState.currentPassword,
                newPassword = newPassword ?: currentState.newPassword,
                confirmPassword = confirmPassword ?: currentState.confirmPassword,
                // Clear field-specific errors when user types
                currentPasswordError = if (currentPassword != null && currentPassword != currentState.currentPassword) null else currentState.currentPasswordError,
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

    /**
     * Maps a [RepositoryError] to a user-friendly login error message.
     * Uses structured checks against [CommonApiErrorCodes] for
     * reliable error identification instead of fragile string matching.
     */
    private fun mapLoginError(error: RepositoryError): String = when {
        error.matches(CommonApiErrorCodes.INVALID_CREDENTIALS) ->
            "Invalid username or password"

        error.matches(CommonApiErrorCodes.VERIFICATION_REQUIRED) ->
            "New login detected. Please check your email to verify your identity."

        error.matches(CommonApiErrorCodes.PERMISSION_DENIED) ->
            "Account is temporarily locked. Please try again later."

        else -> "An unexpected error occurred. Please try again."
    }

    /**
     * Maps a [RepositoryError] to a user-friendly registration error message.
     * Uses structured checks against [CommonApiErrorCodes] for
     * reliable error identification instead of fragile string matching.
     *
     * For [CommonApiErrorCodes.ALREADY_EXISTS], inspects the error details to distinguish
     * between username and email conflicts.
     */
    private fun mapRegistrationError(error: RepositoryError): String = when {
        error.matches(CommonApiErrorCodes.ALREADY_EXISTS) -> {
            // Inspect the underlying ApiError details to determine which field conflicts
            val details = (error as? RepositoryError.DataFetchError)
                ?.apiResourceError
                ?.let { it as? ApiResourceError.ServerError }
                ?.apiError?.details
            val message = (error as? RepositoryError.DataFetchError)
                ?.apiResourceError
                ?.let { it as? ApiResourceError.ServerError }
                ?.apiError?.message ?: ""

            // Check details keys or message content for email-related conflict
            val isEmailConflict = details?.keys?.any { it.contains("email", ignoreCase = true) } == true ||
                message.contains("email", ignoreCase = true)

            if (isEmailConflict) {
                "Email is already registered. Please use a different email or try logging in."
            } else {
                "Username is already taken. Please choose a different one."
            }
        }

        else -> "An unexpected error occurred. Please try again."
    }

    /**
     * Maps a [RepositoryError] to a user-friendly password change error message.
     * Uses structured checks against [CommonApiErrorCodes] for
     * reliable error identification instead of fragile string matching.
     */
    private fun mapPasswordChangeError(error: RepositoryError): String = when {
        error.matches(CommonApiErrorCodes.INVALID_ARGUMENT) ->
            "New password is too weak. Please choose a stronger password."

        error.matches(CommonApiErrorCodes.PERMISSION_DENIED) ->
            "Action requires a trusted session. Please verify your identity."

        error.matches(CommonApiErrorCodes.INVALID_CREDENTIALS) ->
            "Current password is incorrect."

        else -> "Password change failed. Please try again."
    }

    override fun onCleared() {
        super.onCleared()
        normalScope.cancel()
    }
}
