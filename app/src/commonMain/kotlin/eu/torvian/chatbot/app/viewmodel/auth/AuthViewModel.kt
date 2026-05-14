package eu.torvian.chatbot.app.viewmodel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.matches
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
import eu.torvian.chatbot.common.security.SecurityAuditStatus
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
 * - Security alerts management (untrusted device login alerts)
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

    private val _changeEmailFormState = MutableStateFlow(ChangeEmailFormState())
    val changeEmailFormState: StateFlow<ChangeEmailFormState> = _changeEmailFormState.asStateFlow()

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

    // --- Device Verification State ---

    /**
     * Indicates whether a device verification email request is in progress.
     */
    private val _isRequestingVerification = MutableStateFlow(false)
    val isRequestingVerification: StateFlow<Boolean> = _isRequestingVerification.asStateFlow()

    /**
     * Indicates whether a verification email has been sent.
     */
    private val _verificationEmailSent = MutableStateFlow(false)
    val verificationEmailSent: StateFlow<Boolean> = _verificationEmailSent.asStateFlow()

    /**
     * Error message for verification operations, if any.
     */
    private val _verificationError = MutableStateFlow<String?>(null)
    val verificationError: StateFlow<String?> = _verificationError.asStateFlow()

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
                    generalError = null,
                    showVerificationTrigger = false,
                    verificationMessage = null,
                    isVerificationSuccess = false
                )
            }

            // Perform login
            val result = authRepository.login(username, password)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Login failed for user $username: ${error.message}")
                    if (error.matches(CommonApiErrorCodes.VERIFICATION_REQUIRED)) {
                        // Show verification trigger for public device verification
                        _loginFormState.update { currentState ->
                            currentState.copy(
                                isLoading = false,
                                showVerificationTrigger = true,
                                generalError = null
                            )
                        }
                    } else {
                        _loginFormState.update { currentState ->
                            currentState.copy(
                                isLoading = false,
                                generalError = error.mapLoginError()
                            )
                        }
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
                    showSecurityAlerts()
                    // Show restricted session info dialog if the session is restricted
                    val currentState = authState.value
                    if (currentState is AuthState.Authenticated && currentState.isRestricted) {
                        openRestrictedSessionInfo()
                    }
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
                            generalError = error.mapRegistrationError()
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
     * Fetches unacknowledged security alerts for the current user and displays them.
     * Called after successful login or when the user navigates to the security alerts view.
     *
     * This method guards against fetching alerts for unauthenticated or restricted sessions,
     * as the server denies this operation for restricted sessions.
     *
     * @param showOnEmpty If true, the security alerts dialog will be shown even if there are no unacknowledged alerts.
     */
    fun showSecurityAlerts(showOnEmpty: Boolean = false) {
        val currentAuthState = authState.value

        if (currentAuthState !is AuthState.Authenticated) {
            securityAlerts.value = emptyList()
            logger.debug("Skipping security alerts fetch because user is not authenticated")
            return
        }

        if (currentAuthState.isRestricted) {
            securityAlerts.value = emptyList()
            logger.info("Skipping security alerts fetch because current session is restricted")
            return
        }

        viewModelScope.launch {
            authRepository.getSecurityAlerts()
                .onLeft { error ->
                    logger.warn("Failed to fetch security alerts: ${error.message}")
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load security alerts"
                    )
                }
                .onRight { alerts ->
                    securityAlerts.value = alerts
                    logger.info("Fetched ${alerts.size} security alerts")
                    if (alerts.isNotEmpty() || showOnEmpty) {
                        _dialogState.value = AuthDialogState.SecurityAlerts(alerts)
                    }
                }
        }
    }


    /**
     * Resolves a single security alert with the specified outcome.
     *
     * This method allows the user to either trust or dismiss a specific security alert.
     * - Trust (true): The device is added to the trusted devices list.
     * - Dismiss (false): The alert is marked as dismissed without adding the device to trusted devices.
     *
     * After success, the security alerts list is refreshed.
     *
     * Note: This operation is not available for restricted sessions.
     *
     * @param alertId The unique identifier of the security alert to resolve.
     * @param trust Whether to trust the device (true) or dismiss the alert (false).
     */
    fun resolveAlert(alertId: Long, trust: Boolean) {
        viewModelScope.launch {
            val outcome = if (trust) SecurityAuditStatus.TRUSTED else SecurityAuditStatus.DISMISSED
            authRepository.resolveSecurityAlert(alertId, outcome)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to resolve security alert"
                    )
                }
                .onRight {
                    logger.info("Successfully resolved security alert $alertId with outcome: $outcome")
                    // Refresh the alerts list to remove the resolved alert
                    showSecurityAlerts(showOnEmpty = true)
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
            val currentPasswordError =
                if (currentForm.currentPassword.isBlank()) "Current password is required" else null
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
                            generalError = error.mapPasswordChangeError()
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
                            generalError = error.mapPasswordChangeError()
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
     * Changes the email address for the currently authenticated user.
     */
    fun changeEmail() {
        viewModelScope.launch {
            val currentForm = _changeEmailFormState.value
            val currentPassword = currentForm.currentPassword
            val newEmail = currentForm.newEmail

            logger.info("Attempting email change")

            // Validate form before submission
            val currentPasswordError =
                if (currentPassword.isBlank()) "Current password is required" else null
            val newEmailError = authValidationService.validateEmail(newEmail)

            if (currentPasswordError != null || newEmailError != null) {
                _changeEmailFormState.update { currentState ->
                    currentState.copy(
                        currentPasswordError = currentPasswordError,
                        newEmailError = newEmailError,
                        generalError = null
                    )
                }
                return@launch
            }

            // Clear errors and set loading state
            _changeEmailFormState.update { currentState ->
                currentState.copy(
                    isLoading = true,
                    currentPasswordError = null,
                    newEmailError = null,
                    generalError = null
                )
            }

            // Perform email change using the repository
            val result = authRepository.changeEmail(currentPassword, newEmail)

            result.fold(
                ifLeft = { error ->
                    logger.warn("Email change failed: ${error.message}")
                    _changeEmailFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = error.mapEmailChangeError()
                        )
                    }
                },
                ifRight = {
                    logger.info("Email change successful")
                    notificationService.genericSuccess("Your email address has been updated.")
                    _dialogState.value = AuthDialogState.None
                    _changeEmailFormState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            generalError = null,
                            emailChangeSuccessEvent = true
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
        val currentState = authRepository.authState.value
        if (currentState is AuthState.Authenticated) {
            showSecurityAlerts()
            // Show restricted session info dialog if the session is restricted
            if (currentState.isRestricted) {
                openRestrictedSessionInfo()
            }
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
            // Clear account-scoped security state to avoid stale data from the previous account.
            securityAlerts.value = emptyList()
            activeSessions.value = emptyList()
            trustedDevices.value = emptyList()

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
                    showSecurityAlerts()
                    // Show restricted session info dialog if the new account is restricted
                    val newState = authState.value
                    if (newState is AuthState.Authenticated && newState.isRestricted) {
                        openRestrictedSessionInfo()
                    }
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
     * Opens the change email dialog.
     */
    fun openChangeEmailDialog() {
        _dialogState.value = AuthDialogState.ChangeEmail
    }

    /**
     * Opens the restricted session info dialog.
     * This dialog explains to the user why their session has limited permissions.
     */
    fun openRestrictedSessionInfo() {
        _dialogState.value = AuthDialogState.RestrictedSessionInfo
    }

    /**
     * Closes any open authentication dialog.
     */
    fun closeDialog() {
        _dialogState.value = AuthDialogState.None
    }

    // --- Device Verification Operations ---

    /**
     * Requests a device verification email to be sent to the user's registered email address.
     *
     * This method is used for restricted sessions to allow users to verify their device
     * via email. On success, the verification email sent state is set to true.
     * On RATE_LIMIT error, the retryAfterSeconds is extracted and formatted into a user-friendly message.
     */
    fun requestVerificationEmail() {
        viewModelScope.launch {
            _isRequestingVerification.value = true
            _verificationError.value = null

            val deviceId = authState.value.let { state ->
                if (state is AuthState.Authenticated) state.deviceId else null
            }

            if (deviceId == null) {
                _verificationError.value = "Unable to request verification: no device ID available."
                _isRequestingVerification.value = false
                return@launch
            }

            authRepository.requestDeviceVerification(deviceId)
                .onLeft { error ->
                    _verificationError.value = error.mapVerificationError()
                    logger.warn("Failed to request device verification: ${error.message}")
                }
                .onRight {
                    _verificationEmailSent.value = true
                    logger.info("Device verification email sent successfully")
                }

            _isRequestingVerification.value = false
        }
    }

    /**
     * Checks the verification status by refreshing the session.
     *
     * This method calls refreshSession to obtain a new token. If the user has clicked
     * the email verification link, the server will issue a new token with isRestricted = false.
     * On success with unrestricted status, shows a success notification and closes the dialog.
     * If the session remains restricted, shows an informative notification guiding the user.
     */
    fun checkVerificationStatus() {
        viewModelScope.launch {
            _isRequestingVerification.value = true
            _verificationError.value = null

            authRepository.refreshSession()
                .onLeft { error ->
                    _verificationError.value = "Failed to check verification status. Please try again."
                    logger.warn("Failed to refresh session: ${error.message}")
                }
                .onRight {
                    val currentState = authState.value
                    if (currentState is AuthState.Authenticated && !currentState.isRestricted) {
                        notificationService.genericSuccess("Device verified!")
                        _dialogState.value = AuthDialogState.None
                        _verificationEmailSent.value = false
                        logger.info("Session is now unrestricted")
                    } else {
                        notificationService.genericSuccess(
                            "Session is still restricted. Please ensure you have approved the login via the email link or from another trusted device."
                        )
                        logger.info("Session still restricted after refresh")
                    }
                }
            _isRequestingVerification.value = false
        }
    }

    /**
     * Requests a public device verification email for a new device.
     *
     * This method is used when login fails with VERIFICATION_REQUIRED error.
     * It calls the public API endpoint to request a verification email.
     * On success, updates the login form state with a success message.
     * On RATE_LIMIT error, extracts retryAfterSeconds and formats a user-friendly message.
     */
    fun requestPublicVerification() {
        viewModelScope.launch {
            val username = _loginFormState.value.username

            _loginFormState.update { currentState ->
                currentState.copy(
                    isVerifying = true,
                    verificationMessage = null
                )
            }

            authRepository.requestPublicDeviceVerification(username)
                .onLeft { error ->
                    val errorMessage = error.mapVerificationError()
                    _loginFormState.update { currentState ->
                        currentState.copy(
                            isVerifying = false,
                            verificationMessage = errorMessage,
                            isVerificationSuccess = false
                        )
                    }
                    logger.warn("Failed to request public device verification: ${error.message}")
                }
                .onRight {
                    _loginFormState.update { currentState ->
                        currentState.copy(
                            isVerifying = false,
                            verificationMessage = "Verification email sent! Please check your inbox and click the link to verify your device.",
                            isVerificationSuccess = true,
                            showVerificationTrigger = false
                        )
                    }
                    logger.info("Public device verification email sent successfully")
                }
        }
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
     * Updates the change email form state with optional named parameters for each field.
     * Only the provided fields will be updated; others remain unchanged.
     * Field-specific errors are cleared if the corresponding field is updated.
     */
    fun updateChangeEmailForm(
        currentPassword: String? = null,
        newEmail: String? = null
    ) {
        _changeEmailFormState.update { currentState ->
            currentState.copy(
                currentPassword = currentPassword ?: currentState.currentPassword,
                newEmail = newEmail ?: currentState.newEmail,
                // Clear field-specific errors when user types
                currentPasswordError = if (currentPassword != null && currentPassword != currentState.currentPassword) null else currentState.currentPasswordError,
                newEmailError = if (newEmail != null && newEmail != currentState.newEmail) null else currentState.newEmailError
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
     * Clears the change email form state.
     */
    fun clearChangeEmailForm() {
        _changeEmailFormState.value = ChangeEmailFormState()
    }

    /**
     * Clears all form states.
     */
    fun clearAllForms() {
        clearLoginForm()
        clearRegisterForm()
        clearPasswordChangeForm()
        clearChangeEmailForm()
    }

    override fun onCleared() {
        super.onCleared()
        normalScope.cancel()
    }
}
