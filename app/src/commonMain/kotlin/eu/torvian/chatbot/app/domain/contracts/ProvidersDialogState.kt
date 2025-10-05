package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Consolidated state for all dialog management in the ProvidersTab.
 * Each dialog state that involves a form now contains its own form state.
 */
sealed class ProvidersDialogState {
    object None : ProvidersDialogState()

    data class AddNewProvider(
        val formState: ProviderFormState = ProviderFormState(mode = FormMode.NEW)
    ) : ProvidersDialogState()

    data class EditProvider(
        val provider: LLMProvider,
        val formState: ProviderFormState = ProviderFormState.fromProvider(provider),
        val isUpdatingCredential: Boolean = false
    ) : ProvidersDialogState()

    data class DeleteProvider(val provider: LLMProvider) : ProvidersDialogState()
}
