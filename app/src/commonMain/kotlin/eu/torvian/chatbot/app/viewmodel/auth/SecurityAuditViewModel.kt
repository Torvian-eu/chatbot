package eu.torvian.chatbot.app.viewmodel.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.service.clipboard.ClipboardService
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert
import eu.torvian.chatbot.common.models.api.auth.UserSessionInfo
import eu.torvian.chatbot.common.models.api.auth.UserTrustedDeviceInfo
import eu.torvian.chatbot.common.security.SecurityAuditStatus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * ViewModel for managing security audit operations including active sessions,
 * trusted devices, and security alerts.
 *
 * This ViewModel handles:
 * - Active sessions management
 * - Trusted devices management
 * - Security alerts display and resolution
 * - Device verification flows
 *
 * @param authRepository Repository for authentication operations.
 * @param notificationService Service for handling and notifying about errors.
 * @param clipboardService Service for clipboard operations.
 * @param normalScope Coroutine scope for normal operations.
 */
class SecurityAuditViewModel(
    private val authRepository: AuthRepository,
    private val notificationService: NotificationService,
    private val clipboardService: ClipboardService,
    private val normalScope: CoroutineScope
) : ViewModel(normalScope) {

    companion object {
        private val logger = kmpLogger<SecurityAuditViewModel>()
    }

    // --- Authentication State (delegated to repository) ---

    /**
     * The current authentication state from the repository.
     */
    val authState: StateFlow<AuthState> = authRepository.authState

    // --- Security State ---

    /**
     * The active sessions currently fetched from the server for the security dialog.
     */
    private val _activeSessions = MutableStateFlow<List<UserSessionInfo>>(emptyList())
    val activeSessions: StateFlow<List<UserSessionInfo>> = _activeSessions.asStateFlow()

    /**
     * Unacknowledged security alerts for the current user.
     * These represent login attempts from unrecognized devices.
     */
    private val _securityAlerts = MutableStateFlow<List<UserSecurityAlert>>(emptyList())
    val securityAlerts: StateFlow<List<UserSecurityAlert>> = _securityAlerts.asStateFlow()

    /**
     * Trusted devices for the current user.
     * These are devices that have been trusted through first use or security alert acknowledgement.
     */
    private val _trustedDevices = MutableStateFlow<List<UserTrustedDeviceInfo>>(emptyList())
    val trustedDevices: StateFlow<List<UserTrustedDeviceInfo>> = _trustedDevices.asStateFlow()

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

    private val _dialogState = MutableStateFlow<SecurityDialogState>(SecurityDialogState.None)
    val dialogState: StateFlow<SecurityDialogState> = _dialogState.asStateFlow()

    // --- Initialization ---

    init {
        // Reactively handle authentication state changes.
        // Maps to userId and isRestricted, then uses distinctUntilChanged to prevent
        // duplicate processing when only non-relevant properties change.
        // This single pipeline ensures state is cleared BEFORE dialogs are opened.
        viewModelScope.launch {
            authRepository.authState
                .map { state ->
                    val auth = state as? AuthState.Authenticated
                    auth?.userId to auth?.isRestricted
                }
                .distinctUntilChanged()
                .collect { (userId, isRestricted) ->
                    // Always clear security state when identity changes (including logout)
                    // This ensures stale data from the previous account is not shown
                    _activeSessions.value = emptyList()
                    _trustedDevices.value = emptyList()
                    _securityAlerts.value = emptyList()
                    logger.debug("Security state cleared due to identity change")

                    if (userId == null) {
                        // On logout, ensure all security dialogs are closed
                        closeDialog()
                    } else {
                        if (isRestricted == true) {
                            openRestrictedSessionInfo()
                        } else {
                            // If we just became unrestricted, close the Restricted Info dialog
                            // so it doesn't stay stuck open when showSecurityAlerts() is a no-op
                            if (_dialogState.value is SecurityDialogState.RestrictedSessionInfo) {
                                closeDialog()
                            }
                            // Then check if we should show alerts instead
                            showSecurityAlerts()
                        }
                    }
                }
        }
    }

    // --- Security Operations ---

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
                    _activeSessions.value = sessions
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
            _securityAlerts.value = emptyList()
            logger.debug("Skipping security alerts fetch because user is not authenticated")
            return
        }

        if (currentAuthState.isRestricted) {
            _securityAlerts.value = emptyList()
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
                    _securityAlerts.value = alerts
                    logger.info("Fetched ${alerts.size} security alerts")
                    if (alerts.isNotEmpty() || showOnEmpty) {
                        _dialogState.value = SecurityDialogState.SecurityAlerts(alerts)
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
                    _trustedDevices.value = devices
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
                        _dialogState.value = SecurityDialogState.None
                        _verificationEmailSent.value = false
                        logger.info("Session is now unrestricted")
                    } else {
                        _verificationError.value =
                            "Session is still restricted. Please ensure you have approved the login via the email link or from another trusted device."
                        logger.info("Session still restricted after refresh")
                    }
                }
            _isRequestingVerification.value = false
        }
    }

    // --- Dialog State Management ---

    /**
     * Opens the active sessions security dialog.
     */
    fun openActiveSessions() {
        _dialogState.value = SecurityDialogState.ActiveSessions
    }

    /**
     * Opens the trusted devices dialog.
     */
    fun openTrustedDevices() {
        _dialogState.value = SecurityDialogState.TrustedDevices
    }

    /**
     * Opens the restricted session info dialog.
     * This dialog explains to the user why their session has limited permissions.
     */
    fun openRestrictedSessionInfo() {
        _dialogState.value = SecurityDialogState.RestrictedSessionInfo
    }

    /**
     * Closes any open authentication dialog.
     */
    fun closeDialog() {
        _dialogState.value = SecurityDialogState.None
    }

    // --- Clipboard Operations ---

    /**
     * Copies the specified text to the clipboard.
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

    override fun onCleared() {
        super.onCleared()
        normalScope.cancel()
    }
}
