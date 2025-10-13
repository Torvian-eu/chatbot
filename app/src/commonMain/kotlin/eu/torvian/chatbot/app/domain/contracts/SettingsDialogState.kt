package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Consolidated state for all dialog management in the SettingsTab.
 * Each dialog state that involves a form now contains its own form state.
 */
sealed class SettingsDialogState {
    object None : SettingsDialogState()

    data class AddNewSettings(
        val formState: SettingsFormState
    ) : SettingsDialogState()

    data class EditSettings(
        val settings: ModelSettings,
        val formState: SettingsFormState
    ) : SettingsDialogState()

    data class DeleteSettings(val settings: ModelSettings) : SettingsDialogState()

    data class ManageAccess(
        val settingsDetails: ModelSettingsDetails,
        val availableGroups: List<UserGroup>,
        val showGrantDialog: Boolean = false,
        val grantAccessForm: GrantAccessFormState = GrantAccessFormState()
    ) : SettingsDialogState()
}
