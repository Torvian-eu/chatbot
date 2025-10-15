package eu.torvian.chatbot.app.viewmodel.auth

import eu.torvian.chatbot.app.service.auth.AccountData

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

    /**
     * Remove account confirmation dialog is shown.
     *
     * @property account The account to be removed
     */
    data class RemoveAccountConfirmation(
        val account: AccountData
    ) : AuthDialogState
}
