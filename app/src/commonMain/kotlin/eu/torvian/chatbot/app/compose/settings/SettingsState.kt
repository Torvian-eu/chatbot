package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.project.ProjectDto
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
 *
 * @property rolesUiState Reactive role list state from the repository.
 * @property selectedRole The role open in the master-detail view, or null on the list page.
 * @property dialogState Add/edit/delete dialog state.
 * @property models Chat-capable models for the form's `model_specific` instruction target picker.
 * @property presets The user's model presets (name-ascending) offered by the form's preset picker.
 * @property tools Enabled tool definitions for the form's tool multi-select.
 * @property modelsById Model lookup map for the detail page.
 * @property presetsById Preset lookup map used by the detail page to render the role's preset and
 *            the model/settings resolved from it.
 * @property settingsById Settings lookup map (unfiltered) for the detail page and the form's
 *            sendability hint.
 * @property toolsById Tool lookup map for the detail page.
 * @property projects The user's projects, used both by the role form's single-project selector and
 *            by the list page's project grouping/filter derivation. Non-null (defaults to empty) so
 *            consumers never have to unwrap an optional.
 */
data class AgentRolesTabState(
    val rolesUiState: DataState<RepositoryError, List<AgentRoleDto>>,
    val selectedRole: AgentRoleDto?,
    val dialogState: AgentRoleDialogState,
    val models: List<LLMModel>,
    val tools: List<ToolDefinition>,
    val presets: List<ModelPresetDto> = emptyList(),
    val modelsById: Map<Long, LLMModel> = emptyMap(),
    val presetsById: Map<Long, ModelPresetDto> = emptyMap(),
    val settingsById: Map<Long, ModelSettings> = emptyMap(),
    val toolsById: Map<Long, ToolDefinition> = emptyMap(),
    val projects: List<ProjectDto> = emptyList()
) {
    /**
     * The Settings → Agent Roles list grouped by project scope (project sections first, ordered by
     * project name, then the "No project" section). Computed on every state construction from the
     * already-loaded role and project streams so grouping stays reactive without extra reloads.
     */
    val roleSections: List<AgentRoleSection> = buildAgentRoleSections(
        roles = rolesUiState.dataOrNull.orEmpty(),
        projects = projects
    )
}
