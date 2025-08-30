package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.EditProviderFormState
import eu.torvian.chatbot.app.domain.contracts.NewProviderFormState
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.ModelSettings

/**
 * State contract for the Providers tab.
 */
data class ProvidersTabState(
    val providersUiState: UiState<ApiError, List<LLMProvider>>,
    val isAddingNewProvider: Boolean,
    val editingProvider: LLMProvider?,
    val newProviderForm: NewProviderFormState,
    val editingProviderForm: EditProviderFormState,
    val credentialUpdateLoading: Boolean
)

/**
 * State contract for the Models tab.
 */
data class ModelsTabState(
    val modelsUiState: UiState<ApiError, List<LLMModel>>
)

/**
 * State contract for the Settings Config tab.
 */
data class SettingsConfigTabState(
    val modelsForSelection: UiState<ApiError, List<LLMModel>>,
    val selectedModelId: Long?,
    val settingsState: UiState<ApiError, List<ModelSettings>>
)
