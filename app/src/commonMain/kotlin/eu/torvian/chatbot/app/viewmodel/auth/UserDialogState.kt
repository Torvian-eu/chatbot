package eu.torvian.chatbot.app.viewmodel.auth

/**
 * Represents the state of dialogs related to user profile operations.
 * Includes change password and change email dialogs.
 */
sealed interface UserDialogState {
    /** No dialog is shown */
    data object None : UserDialogState

    /**
     * Change password dialog is shown.
     */
    data object ChangePassword : UserDialogState

    /**
     * Change email dialog is shown.
     */
    data object ChangeEmail : UserDialogState
}
