package eu.torvian.chatbot.app.compose.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.service.auth.AuthValidationService
import eu.torvian.chatbot.app.viewmodel.auth.AuthDialogState
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel
import org.koin.compose.getKoin

/**
 * Container for all authentication-related dialogs.
 *
 * Renders the appropriate dialog based on the current dialog state.
 */
@Composable
fun AuthDialogs(
    dialogState: AuthDialogState,
    availableAccounts: List<AccountData>,
    currentAuthState: AuthState,
    accountSwitchInProgress: Boolean,
    authViewModel: AuthViewModel
) {
    val activeSessions by authViewModel.activeSessions.collectAsState()

    when (dialogState) {
        is AuthDialogState.SwitchAccount -> {
            SwitchAccountDialog(
                availableAccounts = availableAccounts,
                currentAuthState = currentAuthState,
                accountSwitchInProgress = accountSwitchInProgress,
                onDismiss = { authViewModel.closeDialog() },
                onSwitchAccount = { userId -> authViewModel.switchAccount(userId) },
                onRemoveAccount = { account -> authViewModel.openRemoveAccountConfirmation(account) }
            )
        }

        is AuthDialogState.AddAccount -> {
            val loginFormState by authViewModel.loginFormState.collectAsState()

            AddAccountDialog(
                loginFormState = loginFormState,
                onDismiss = {
                    authViewModel.clearLoginForm()
                    authViewModel.closeDialog()
                },
                onUsernameChange = { username -> authViewModel.updateLoginForm(username = username) },
                onPasswordChange = { password -> authViewModel.updateLoginForm(password = password) },
                onLogin = { authViewModel.login() }
            )
        }

        is AuthDialogState.ActiveSessions -> {
            LaunchedEffect(dialogState) {
                authViewModel.refreshSessions()
            }

            val isCurrentSessionRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted

            ActiveSessionsDialog(
                sessions = activeSessions,
                currentAuthState = currentAuthState,
                isCurrentSessionRestricted = isCurrentSessionRestricted,
                onDismiss = { authViewModel.closeDialog() },
                onRevokeSession = { sessionId: Long -> authViewModel.revokeSession(sessionId) },
                onCopyToClipboard = authViewModel::copyToClipboard
            )
        }

        is AuthDialogState.RemoveAccountConfirmation -> {
            RemoveAccountConfirmationDialog(
                account = dialogState.account,
                currentAuthState = currentAuthState,
                onDismiss = { authViewModel.closeDialog() },
                onConfirm = { authViewModel.removeAccount(dialogState.account.user.id) }
            )
        }

        is AuthDialogState.TrustedDevices -> {
            LaunchedEffect(dialogState) {
                authViewModel.refreshTrustedDevices()
            }

            val trustedDevices by authViewModel.trustedDevices.collectAsState()
            val currentDeviceId = (currentAuthState as? AuthState.Authenticated)?.deviceId
            val isCurrentSessionRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted

            TrustedDevicesDialog(
                devices = trustedDevices,
                currentDeviceId = currentDeviceId,
                isCurrentSessionRestricted = isCurrentSessionRestricted,
                onDismiss = { authViewModel.closeDialog() },
                onRevokeDevice = { deviceId -> authViewModel.revokeTrustedDevice(deviceId) },
                onCopyToClipboard = authViewModel::copyToClipboard
            )
        }

        is AuthDialogState.ChangePassword -> {
            val passwordFormState by authViewModel.passwordChangeFormState.collectAsState()
            val isRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted
            val passwordValidationConfig = getKoin().get<AuthValidationService>().passwordValidationConfig

            ChangePasswordDialog(
                formState = passwordFormState,
                isRestricted = isRestricted,
                passwordValidationConfig = passwordValidationConfig,
                onDismiss = {
                    authViewModel.clearPasswordChangeForm()
                    authViewModel.closeDialog()
                },
                onCurrentPasswordChange = { password -> authViewModel.updatePasswordChangeForm(currentPassword = password) },
                onNewPasswordChange = { password -> authViewModel.updatePasswordChangeForm(newPassword = password) },
                onConfirmPasswordChange = { password -> authViewModel.updatePasswordChangeForm(confirmPassword = password) },
                onChangePassword = { authViewModel.changePassword() }
            )
        }

        is AuthDialogState.ChangeEmail -> {
            val changeEmailFormState by authViewModel.changeEmailFormState.collectAsState()
            val isRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted

            ChangeEmailDialog(
                formState = changeEmailFormState,
                isRestricted = isRestricted,
                onDismiss = {
                    authViewModel.clearChangeEmailForm()
                    authViewModel.closeDialog()
                },
                onCurrentPasswordChange = { password -> authViewModel.updateChangeEmailForm(currentPassword = password) },
                onNewEmailChange = { email -> authViewModel.updateChangeEmailForm(newEmail = email) },
                onChangeEmail = { authViewModel.changeEmail() }
            )
        }

        is AuthDialogState.RestrictedSessionInfo -> {
            val isRequestingVerification by authViewModel.isRequestingVerification.collectAsState()
            val verificationEmailSent by authViewModel.verificationEmailSent.collectAsState()
            val verificationError by authViewModel.verificationError.collectAsState()

            RestrictedSessionInfoDialog(
                isRequestingVerification = isRequestingVerification,
                verificationEmailSent = verificationEmailSent,
                verificationError = verificationError,
                onRequestVerificationEmail = { authViewModel.requestVerificationEmail() },
                onCheckVerificationStatus = { authViewModel.checkVerificationStatus() },
                onDismiss = { authViewModel.closeDialog() }
            )
        }

        is AuthDialogState.SecurityAlerts -> {
            val isRestricted = currentAuthState is AuthState.Authenticated && currentAuthState.isRestricted
            SecurityAlertsDialog(
                alerts = dialogState.alerts,
                isRestricted = isRestricted,
                onDismiss = { authViewModel.closeDialog() },
                onResolveAlert = { alertId, trust ->
                    authViewModel.resolveAlert(alertId, trust)
                },
                onCopyToClipboard = { alert ->
                    authViewModel.copyToClipboard(alert.deviceId)
                }
            )
        }

        AuthDialogState.None -> {
            // No dialog to show
        }
    }
}
