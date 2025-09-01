package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider

/**
 * State contract for the Providers tab.
 */
data class ProvidersTabState(
    val providersUiState: UiState<ApiError, List<LLMProvider>>,
    val selectedProvider: LLMProvider?,
    val dialogState: ProvidersDialogState
)

/**
 * State contract for the Models tab.
 */
data class ModelsTabState(
    val modelConfigUiState: UiState<ApiError, ModelConfigData>,
    val selectedModel: LLMModel?,
    val dialogState: ModelsDialogState
)

/**
 * State contract for the Settings Config tab.
 */
data class SettingsConfigTabState(
    val settingsConfigState: UiState<ApiError, SettingsConfigData>,
    val selectedModelId: Long?,
    val dialogState: SettingsDialogState
)
