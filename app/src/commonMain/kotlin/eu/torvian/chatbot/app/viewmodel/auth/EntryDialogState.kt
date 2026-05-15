package eu.torvian.chatbot.app.viewmodel.auth

/**
 * Represents the state of dialogs related to authentication entry flows.
 * Includes login, registration, and add account dialogs.
 */
sealed interface EntryDialogState {
    /** No dialog is shown */
    data object None : EntryDialogState

    /** Add account dialog is shown */
    data object AddAccount : EntryDialogState
}
