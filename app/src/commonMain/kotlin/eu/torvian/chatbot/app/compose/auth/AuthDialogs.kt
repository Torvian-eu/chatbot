package eu.torvian.chatbot.app.compose.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.auth.AuthValidationService
import eu.torvian.chatbot.app.viewmodel.auth.AccountDialogState
import eu.torvian.chatbot.app.viewmodel.auth.AccountManagementViewModel
import eu.torvian.chatbot.app.viewmodel.auth.EntryDialogState
import eu.torvian.chatbot.app.viewmodel.auth.AuthEntryViewModel
import eu.torvian.chatbot.app.viewmodel.auth.SecurityDialogState
import eu.torvian.chatbot.app.viewmodel.auth.SecurityAuditViewModel
import eu.torvian.chatbot.app.viewmodel.auth.UserDialogState
import eu.torvian.chatbot.app.viewmodel.auth.UserProfileViewModel
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel

/**
 * Container for all authentication-related dialogs.
 *
 * Resolves its own ViewModels and collects their dialog states internally,
 * rendering the appropriate dialog based on the current states.
 *
 * @param currentAuthState The current authentication state (used for security audit dialogs).
 */
@Composable
fun AuthDialogs(currentAuthState: AuthState) {
    // Resolve ViewModels locally
    val authEntryViewModel: AuthEntryViewModel = koinViewModel()
    val accountManagementViewModel: AccountManagementViewModel = koinViewModel()
    val securityAuditViewModel: SecurityAuditViewModel = koinViewModel()
    val userProfileViewModel: UserProfileViewModel = koinViewModel()

    // Render entry dialogs
    EntryDialogs(authEntryViewModel = authEntryViewModel)

    // Render account management dialogs
    AccountManagementDialogs(
        currentAuthState = currentAuthState,
        accountManagementViewModel = accountManagementViewModel
    )

    // Render security audit dialogs
    SecurityAuditDialogs(
        currentAuthState = currentAuthState,
        securityAuditViewModel = securityAuditViewModel
    )

    // Render user profile dialogs
    UserDialogs(
        currentAuthState = currentAuthState,
        userProfileViewModel = userProfileViewModel
    )
}

/**
 * Renders entry-related dialogs (add account).
 * Collects its own dialog state from the provided ViewModel.
 */
@Composable
private fun EntryDialogs(authEntryViewModel: AuthEntryViewModel) {
    val dialogState by authEntryViewModel.dialogState.collectAsState()

    when (dialogState) {
        is EntryDialogState.AddAccount -> {
            val loginFormState by authEntryViewModel.loginFormState.collectAsState()

            AddAccountDialog(
                loginFormState = loginFormState,
                onDismiss = {
                    authEntryViewModel.clearLoginForm()
                    authEntryViewModel.closeDialog()
                },
                onUsernameChange = { username -> authEntryViewModel.updateLoginForm(username = username) },
                onPasswordChange = { password -> authEntryViewModel.updateLoginForm(password = password) },
                onLogin = { authEntryViewModel.login() }
            )
        }

        EntryDialogState.None -> {
            // No dialog to show
        }
    }
}

/**
 * Renders account management dialogs (switch account, remove account).
 * Collects its own dialog state and related data from the provided ViewModel.
 */
@Composable
private fun AccountManagementDialogs(
    currentAuthState: AuthState,
    accountManagementViewModel: AccountManagementViewModel
) {
    val dialogState by accountManagementViewModel.dialogState.collectAsState()
    val availableAccounts by accountManagementViewModel.availableAccounts.collectAsState()
    val accountSwitchInProgress by accountManagementViewModel.accountSwitchInProgress.collectAsState()

    // Extract the dialogState value to enable smart cast
    when (val state = dialogState) {
        is AccountDialogState.SwitchAccount -> {
            SwitchAccountDialog(
                availableAccounts = availableAccounts,
                currentAuthState = currentAuthState,
                accountSwitchInProgress = accountSwitchInProgress,
                onDismiss = { accountManagementViewModel.closeDialog() },
                onSwitchAccount = { userId -> accountManagementViewModel.switchAccount(userId) },
                onRemoveAccount = { account -> accountManagementViewModel.openRemoveAccountConfirmation(account) }
            )
        }

        is AccountDialogState.RemoveAccountConfirmation -> {
            RemoveAccountConfirmationDialog(
                account = state.account,
                currentAuthState = currentAuthState,
                onDismiss = { accountManagementViewModel.closeDialog() },
                onConfirm = { accountManagementViewModel.removeAccount(state.account.user.id) }
            )
        }

        AccountDialogState.None -> {
            // No dialog to show
        }
    }
}

/**
 * Renders security audit dialogs (active sessions, trusted devices, security alerts, restricted session info).
 * Collects its own dialog state and related data from the provided ViewModel.
 */
@Composable
private fun SecurityAuditDialogs(
    currentAuthState: AuthState,
    securityAuditViewModel: SecurityAuditViewModel
) {
    val dialogState by securityAuditViewModel.dialogState.collectAsState()
    val activeSessions by securityAuditViewModel.activeSessions.collectAsState()
    val trustedDevices by securityAuditViewModel.trustedDevices.collectAsState()

    // Extract the dialogState value to enable smart cast
    when (val state = dialogState) {
        is SecurityDialogState.ActiveSessions -> {
            LaunchedEffect(state) {
                securityAuditViewModel.refreshSessions()
            }

            val isCurrentSessionRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted

            ActiveSessionsDialog(
                sessions = activeSessions,
                currentAuthState = currentAuthState,
                isCurrentSessionRestricted = isCurrentSessionRestricted,
                onDismiss = { securityAuditViewModel.closeDialog() },
                onRevokeSession = { sessionId: Long -> securityAuditViewModel.revokeSession(sessionId) },
                onCopyToClipboard = securityAuditViewModel::copyToClipboard
            )
        }

        is SecurityDialogState.TrustedDevices -> {
            LaunchedEffect(state) {
                securityAuditViewModel.refreshTrustedDevices()
            }

            val currentDeviceId = (currentAuthState as? AuthState.Authenticated)?.deviceId
            val isCurrentSessionRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted

            TrustedDevicesDialog(
                devices = trustedDevices,
                currentDeviceId = currentDeviceId,
                isCurrentSessionRestricted = isCurrentSessionRestricted,
                onDismiss = { securityAuditViewModel.closeDialog() },
                onRevokeDevice = { deviceId -> securityAuditViewModel.revokeTrustedDevice(deviceId) },
                onCopyToClipboard = securityAuditViewModel::copyToClipboard
            )
        }

        is SecurityDialogState.SecurityAlerts -> {
            val isRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted
            SecurityAlertsDialog(
                alerts = state.alerts,
                isRestricted = isRestricted,
                onDismiss = { securityAuditViewModel.closeDialog() },
                onResolveAlert = { alertId, trust ->
                    securityAuditViewModel.resolveAlert(alertId, trust)
                },
                onCopyToClipboard = { alert ->
                    securityAuditViewModel.copyToClipboard(alert.deviceId)
                }
            )
        }

        is SecurityDialogState.RestrictedSessionInfo -> {
            val isRequestingVerification by securityAuditViewModel.isRequestingVerification.collectAsState()
            val verificationEmailSent by securityAuditViewModel.verificationEmailSent.collectAsState()
            val verificationError by securityAuditViewModel.verificationError.collectAsState()

            RestrictedSessionInfoDialog(
                isRequestingVerification = isRequestingVerification,
                verificationEmailSent = verificationEmailSent,
                verificationError = verificationError,
                onRequestVerificationEmail = { securityAuditViewModel.requestVerificationEmail() },
                onCheckVerificationStatus = { securityAuditViewModel.checkVerificationStatus() },
                onDismiss = { securityAuditViewModel.closeDialog() }
            )
        }

        SecurityDialogState.None -> {
            // No dialog to show
        }
    }
}

/**
 * Renders user profile dialogs (change password, change email).
 * Collects its own dialog state and related data from the provided ViewModel.
 */
@Composable
private fun UserDialogs(
    currentAuthState: AuthState,
    userProfileViewModel: UserProfileViewModel
) {
    val dialogState by userProfileViewModel.dialogState.collectAsState()

    // Extract the dialogState value to enable smart cast
    when (dialogState) {
        is UserDialogState.ChangePassword -> {
            val passwordFormState by userProfileViewModel.passwordChangeFormState.collectAsState()
            val isRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted
            val passwordValidationConfig = getKoin().get<AuthValidationService>().passwordValidationConfig

            ChangePasswordDialog(
                formState = passwordFormState,
                isRestricted = isRestricted,
                passwordValidationConfig = passwordValidationConfig,
                onDismiss = {
                    userProfileViewModel.clearPasswordChangeForm()
                    userProfileViewModel.closeDialog()
                },
                onCurrentPasswordChange = { password -> userProfileViewModel.updatePasswordChangeForm(currentPassword = password) },
                onNewPasswordChange = { password -> userProfileViewModel.updatePasswordChangeForm(newPassword = password) },
                onConfirmPasswordChange = { password -> userProfileViewModel.updatePasswordChangeForm(confirmPassword = password) },
                onChangePassword = { userProfileViewModel.changePassword() }
            )
        }

        is UserDialogState.ChangeEmail -> {
            val changeEmailFormState by userProfileViewModel.changeEmailFormState.collectAsState()
            val isRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted

            ChangeEmailDialog(
                formState = changeEmailFormState,
                isRestricted = isRestricted,
                onDismiss = {
                    userProfileViewModel.clearChangeEmailForm()
                    userProfileViewModel.closeDialog()
                },
                onCurrentPasswordChange = { password -> userProfileViewModel.updateChangeEmailForm(currentPassword = password) },
                onNewEmailChange = { email -> userProfileViewModel.updateChangeEmailForm(newEmail = email) },
                onChangeEmail = { userProfileViewModel.changeEmail() }
            )
        }

        UserDialogState.None -> {
            // No dialog to show
        }
    }
}
