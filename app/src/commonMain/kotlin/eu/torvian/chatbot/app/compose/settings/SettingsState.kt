package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.app.domain.contracts.ModelConfigData
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
    val isEditingProvider: Boolean,
    val editingProvider: LLMProvider?,
    val providerForm: ProviderFormState,
    val credentialUpdateLoading: Boolean
)

/**
 * State contract for the Models tab.
 */
data class ModelsTabState(
    val modelConfigUiState: UiState<ApiError, ModelConfigData>,
    val isAddingNewModel: Boolean,
    val modelForm: ModelFormState,
    val editingModel: LLMModel?,
    val selectedModel: LLMModel?
)

/**
 * State contract for the Settings Config tab.
 */
data class SettingsConfigTabState(
    val modelsForSelection: UiState<ApiError, List<LLMModel>>,
    val selectedModelId: Long?,
    val settingsState: UiState<ApiError, List<ModelSettings>>
)
