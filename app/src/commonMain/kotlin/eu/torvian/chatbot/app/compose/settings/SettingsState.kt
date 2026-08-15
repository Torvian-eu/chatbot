package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.worker.WorkerDto

/**
 * State contract for the Providers tab.
 */
data class ProvidersTabState(
    val providersUiState: DataState<RepositoryError, List<LLMProviderDetails>>,
    val selectedProvider: LLMProviderDetails?,
    val dialogState: ProvidersDialogState
)

/**
 * State contract for the Models tab.
 */
data class ModelsTabState(
    val modelConfigUiState: DataState<RepositoryError, ModelConfigData>,
    val selectedModel: LLMModelDetails?,
    val dialogState: ModelsDialogState
)

/**
 * State contract for the Settings Config tab.
 */
data class ModelSettingsConfigTabState(
    val modelsUiState: DataState<RepositoryError, List<LLMModel>>,
    val settingsListForSelectedModel: List<ModelSettingsDetails>?,
    val selectedModel: LLMModel?,
    val selectedSettings: ModelSettingsDetails?,
    val dialogState: ModelSettingsDialogState
)

/**
 * State contract for the Workers tab.
 */
data class WorkersTabState(
    val workersUiState: DataState<RepositoryError, List<WorkerDto>>,
    val dialogState: WorkersDialogState
)

/**
 * State contract for the Agent Roles tab.
 */
data class AgentRolesTabState(
    val rolesUiState: DataState<RepositoryError, List<AgentRoleDto>>,
    val selectedRole: AgentRoleDto?,
    val dialogState: AgentRoleDialogState,
    val models: List<LLMModel>,
    val settingsForFormModel: List<ModelSettings>?,
    val tools: List<ToolDefinition>,
    val modelsById: Map<Long, LLMModel> = emptyMap(),
    val settingsById: Map<Long, ModelSettings> = emptyMap(),
    val toolsById: Map<Long, ToolDefinition> = emptyMap()
)
