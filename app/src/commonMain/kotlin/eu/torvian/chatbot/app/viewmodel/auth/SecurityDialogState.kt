package eu.torvian.chatbot.app.viewmodel.auth

import eu.torvian.chatbot.common.models.api.auth.UserSecurityAlert

/**
 * Represents the state of dialogs related to security audit operations.
 * Includes active sessions, trusted devices, security alerts, and device verification dialogs.
 */
sealed interface SecurityDialogState {
    /** No dialog is shown */
    data object None : SecurityDialogState

    /** Active sessions dialog is shown */
    data object ActiveSessions : SecurityDialogState

    /** Trusted devices dialog is shown */
    data object TrustedDevices : SecurityDialogState

    /**
     * Restricted session info dialog is shown.
     * This dialog explains to the user why their session has limited permissions.
     */
    data object RestrictedSessionInfo : SecurityDialogState

    /**
     * Security alerts dialog is shown.
     * This dialog displays unacknowledged security alerts for the current user.
     *
     * @property alerts The list of unacknowledged security alerts to display.
     */
    data class SecurityAlerts(
        val alerts: List<UserSecurityAlert>
    ) : SecurityDialogState
}
