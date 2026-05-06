package eu.torvian.chatbot.app.compose.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.viewmodel.auth.AuthDialogState
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel

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
                onRevokeSession = { sessionId: Long -> authViewModel.revokeSession(sessionId) }
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

        AuthDialogState.None -> {
            // No dialog to show
        }
    }
}
