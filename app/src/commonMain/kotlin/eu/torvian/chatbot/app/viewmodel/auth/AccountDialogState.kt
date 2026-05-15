package eu.torvian.chatbot.app.viewmodel.auth

import eu.torvian.chatbot.app.service.auth.AccountData

/**
 * Represents the state of dialogs related to account management.
 * Includes account switching and account removal dialogs.
 */
sealed interface AccountDialogState {
    /** No dialog is shown */
    data object None : AccountDialogState

    /** Account switcher dialog is shown */
    data object SwitchAccount : AccountDialogState

    /**
     * Remove account confirmation dialog is shown.
     *
     * @property account The account to be removed.
     */
    data class RemoveAccountConfirmation(
        val account: AccountData
    ) : AccountDialogState
}
