package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Consolidated state for all dialog management in the Model Settings tab.
 * Each dialog state that involves a form now contains its own form state.
 */
sealed class ModelSettingsDialogState {
    object None : ModelSettingsDialogState()

    data class AddNewSettings(
        val formState: ModelSettingsFormState
    ) : ModelSettingsDialogState()

    data class EditSettings(
        val settings: ModelSettings,
        val formState: ModelSettingsFormState
    ) : ModelSettingsDialogState()

    data class DeleteSettings(val settings: ModelSettings) : ModelSettingsDialogState()

    data class ManageAccess(
        val settingsDetails: ModelSettingsDetails,
        val availableGroups: List<UserGroup>,
        val showGrantDialog: Boolean = false,
        val grantAccessForm: GrantAccessFormState = GrantAccessFormState()
    ) : ModelSettingsDialogState()
}
