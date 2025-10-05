package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * State contract for the Providers tab.
 */
data class ProvidersTabState(
    val providersUiState: DataState<RepositoryError, List<LLMProvider>>,
    val selectedProvider: LLMProvider?,
    val dialogState: ProvidersDialogState
)

/**
 * State contract for the Models tab.
 */
data class ModelsTabState(
    val modelConfigUiState: DataState<RepositoryError, ModelConfigData>,
    val selectedModel: LLMModel?,
    val dialogState: ModelsDialogState
)

/**
 * State contract for the Settings Config tab.
 */
data class SettingsConfigTabState(
    val modelsUiState: DataState<RepositoryError, List<LLMModel>>,
    val settingsListForSelectedModel: List<ModelSettings>?,
    val selectedModel: LLMModel?,
    val selectedSettings: ModelSettings?,
    val dialogState: SettingsDialogState
)
