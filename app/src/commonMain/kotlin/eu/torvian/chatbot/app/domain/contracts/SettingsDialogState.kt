package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.LLMModelType
import eu.torvian.chatbot.common.models.ModelSettings

/**
 * Consolidated state for all dialog management in the SettingsTab.
 * Each dialog state that involves a form now contains its own form state.
 */
sealed class SettingsDialogState {
    object None : SettingsDialogState()

    data class AddNewSettings(
        val formState: SettingsFormState,
        val availableSettingsTypes: List<LLMModelType>,
        val selectedSettingsType: LLMModelType
    ) : SettingsDialogState()

    data class EditSettings(
        val settings: ModelSettings,
        val formState: SettingsFormState
    ) : SettingsDialogState()

    data class DeleteSettings(val settings: ModelSettings) : SettingsDialogState()
}
