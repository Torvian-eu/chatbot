package eu.torvian.chatbot.app.viewmodel.auth

import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert

/**
 * Represents the state of dialogs in the authentication screen.
 */
sealed interface AuthDialogState {
    /** No dialog is shown */
    data object None : AuthDialogState

    /** Account switcher dialog is shown */
    data object SwitchAccount : AuthDialogState

    /** Add account dialog is shown */
    data object AddAccount : AuthDialogState

    /** Active sessions dialog is shown */
    data object ActiveSessions : AuthDialogState

    /** Trusted devices dialog is shown */
    data object TrustedDevices : AuthDialogState

    /**
     * Remove account confirmation dialog is shown.
     *
     * @property account The account to be removed
     */
    data class RemoveAccountConfirmation(
        val account: AccountData
    ) : AuthDialogState

    /**
     * Change password dialog is shown.
     */
    data object ChangePassword : AuthDialogState

    /**
     * Change email dialog is shown.
     */
    data object ChangeEmail : AuthDialogState

    /**
     * Restricted session info dialog is shown.
     * This dialog explains to the user why their session has limited permissions.
     */
    data object RestrictedSessionInfo : AuthDialogState

    /**
     * Security alerts dialog is shown.
     * This dialog displays unacknowledged security alerts for the current user.
     *
     * @property alerts The list of unacknowledged security alerts to display.
     */
    data class SecurityAlerts(
        val alerts: List<UserSecurityAlert>
    ) : AuthDialogState
}
