package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Consolidated state for all dialog management in the ProvidersTab.
 * Each dialog state that involves a form now contains its own form state.
 */
sealed class ProvidersDialogState {
    object None : ProvidersDialogState()

    data class AddNewProvider(
        val formState: ProviderFormState = ProviderFormState(mode = FormMode.NEW),
        val isTestingConnection: Boolean = false
    ) : ProvidersDialogState()

    data class EditProvider(
        val provider: LLMProvider,
        val formState: ProviderFormState = ProviderFormState.fromProvider(provider),
        val isUpdatingCredential: Boolean = false
    ) : ProvidersDialogState()

    data class DeleteProvider(val provider: LLMProvider) : ProvidersDialogState()

    data class ManageAccess(
        val providerDetails: LLMProviderDetails,
        val availableGroups: List<UserGroup>,
        val showGrantDialog: Boolean = false,
        val grantAccessForm: GrantAccessFormState = GrantAccessFormState()
    ) : ProvidersDialogState()
}
