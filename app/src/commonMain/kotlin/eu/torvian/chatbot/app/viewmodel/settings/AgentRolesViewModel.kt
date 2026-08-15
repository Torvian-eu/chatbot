package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.AgentRoleDialogState
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFormState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.createEmptyAgentRoleForm
import eu.torvian.chatbot.app.domain.contracts.toEditFormState
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.ModelSettingsRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for the Agent Roles settings category.
 *
 * The ViewModel owns the role list, the selected role (master-detail), and the dialog/form state,
 * mirroring the [ModelSettingsViewModel] shape. It pulls chat-capable models, settings profiles and
 * enabled tools from their repositories to feed the role form's pickers.
 *
 * @property agentRoleRepository Repository for agent-role CRUD and the reactive role list.
 * @property modelRepository Repository of LLM models (filtered to chat-capable for the form).
 * @property modelSettingsRepository Repository of settings profiles (filtered to chat-capable).
 * @property toolRepository Repository of tool definitions (filtered to enabled tools).
 * @property notificationService Service for error/success notifications.
 * @property uiDispatcher Dispatcher used for UI coroutines. Defaults to Main.
 */
class AgentRolesViewModel(
    private val agentRoleRepository: AgentRoleRepository,
    private val modelRepository: ModelRepository,
    private val modelSettingsRepository: ModelSettingsRepository,
    private val toolRepository: ToolRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    companion object {
        private val logger = kmpLogger<AgentRolesViewModel>()
    }

    private val userSelectedRoleId = MutableStateFlow<Long?>(null)
    private val _dialogState = MutableStateFlow<AgentRoleDialogState>(AgentRoleDialogState.None)

    /** Reactive stream of all agent roles owned by the current user. */
    val rolesState: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>> = agentRoleRepository.roles

    /** The role selected in the master-detail UI, or null when on the list page. */
    val selectedRole: StateFlow<AgentRoleDto?> = combine(
        rolesState.map { it.dataOrNull },
        userSelectedRoleId
    ) { roles, selectedId ->
        roles?.find { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Chat-capable models for the role form's model picker (active + having a CHAT/RESPONSES profile). */
    val modelsState: StateFlow<DataState<RepositoryError, List<LLMModel>>> = combine(
        modelRepository.models,
        modelSettingsRepository.allSettings
    ) { modelsState, settingsState ->
        when (modelsState) {
            is DataState.Success -> {
                val chatCapableModelIds = settingsState.dataOrNull.orEmpty()
                    .filter { isChatCapableSettings(it) }
                    .map { it.modelId }
                    .toSet()
                DataState.Success(modelsState.data.filter { model -> model.active && model.id in chatCapableModelIds })
            }

            is DataState.Error -> modelsState
            is DataState.Loading -> modelsState
            is DataState.Idle -> modelsState
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), DataState.Idle)

    /** Enabled tool definitions available for the role form's tool multi-select. */
    val toolsState: StateFlow<DataState<RepositoryError, List<ToolDefinition>>> = toolRepository.tools
        .map { state ->
            when (state) {
                is DataState.Success -> DataState.Success(state.data.filter { it.isEnabled })
                is DataState.Error -> state
                is DataState.Loading -> state
                is DataState.Idle -> state
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), DataState.Idle)

    /** Model lookup map for the role detail page. */
    val modelsById: StateFlow<Map<Long, LLMModel>> =
        modelRepository.models.map { it.dataOrNull?.associateBy { model -> model.id } ?: emptyMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    /** Settings lookup map (chat-capable profiles) for the role detail page. */
    val settingsById: StateFlow<Map<Long, ModelSettings>> =
        modelSettingsRepository.allSettings
            .map { it.dataOrNull?.filter(::isChatCapableSettings)?.associateBy { s -> s.id } ?: emptyMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    /** Tool lookup map for the role detail page. */
    val toolsById: StateFlow<Map<Long, ToolDefinition>> =
        toolRepository.tools.map { it.dataOrNull?.associateBy { tool -> tool.id } ?: emptyMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    /** Chat-capable settings profiles for the model currently chosen in the form. */
    val settingsForFormModel: StateFlow<List<ModelSettings>?> = combine(
        modelSettingsRepository.allSettings.map { it.dataOrNull },
        _dialogState
    ) { allSettings, currentDialog ->
        val formModelId = when (currentDialog) {
            is AgentRoleDialogState.AddRole -> currentDialog.formState.modelId
            is AgentRoleDialogState.EditRole -> currentDialog.formState.modelId
            else -> null
        }
        allSettings?.filter { settings ->
            isChatCapableSettings(settings) && settings.modelId == formModelId
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** The current dialog state for the tab. */
    val dialogState: StateFlow<AgentRoleDialogState> = _dialogState.asStateFlow()

    /**
     * Loads the role list and the model/settings/tools catalogs in parallel.
     */
    fun loadRolesAndCatalogs() {
        viewModelScope.launch(uiDispatcher) {
            parZip(
                { agentRoleRepository.loadRoles() },
                { modelRepository.loadModels() },
                { modelSettingsRepository.loadAllSettings() },
                { toolRepository.loadTools() }
            ) { rolesResult, modelsResult, settingsResult, toolsResult ->
                rolesResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load agent roles"
                    )
                }
                modelsResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load models"
                    )
                }
                settingsResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load settings"
                    )
                }
                toolsResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load tools"
                    )
                }
            }
        }
    }

    /**
     * Selects an agent role for the master-detail view, or clears selection when null.
     */
    fun selectRole(role: AgentRoleDto?) {
        userSelectedRoleId.value = role?.id
    }

    /**
     * Opens the add-role form dialog with a fresh draft.
     */
    fun startAddingNewRole() {
        _dialogState.value = AgentRoleDialogState.AddRole(
            formState = createEmptyAgentRoleForm()
        )
    }

    /**
     * Opens the edit-role form dialog pre-filled from [role].
     */
    fun startEditingRole(role: AgentRoleDto) {
        _dialogState.value = AgentRoleDialogState.EditRole(
            role = role,
            formState = role.toEditFormState()
        )
    }

    /**
     * Opens the delete-role confirmation dialog for [role].
     */
    fun startDeletingRole(role: AgentRoleDto) {
        _dialogState.value = AgentRoleDialogState.DeleteRole(role)
    }

    /**
     * Applies an update function to the active form draft (add or edit dialog).
     */
    fun updateRoleForm(update: (AgentRoleFormState) -> AgentRoleFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is AgentRoleDialogState.AddRole -> dialogState.copy(formState = update(dialogState.formState))
                is AgentRoleDialogState.EditRole -> dialogState.copy(formState = update(dialogState.formState))
                else -> dialogState
            }
        }
    }

    /**
     * Saves the active form draft: creates a new role for the add dialog, or replaces the
     * configuration for the edit dialog.
     */
    fun saveRole() {
        when (val dialogState = _dialogState.value) {
            is AgentRoleDialogState.AddRole -> saveNewRole(dialogState.formState)
            is AgentRoleDialogState.EditRole -> saveEditedRole(dialogState)
            else -> return
        }
    }

    /**
     * Deletes a role and closes the confirmation dialog.
     */
    fun deleteRole(roleId: Long) {
        viewModelScope.launch(uiDispatcher) {
            agentRoleRepository.deleteRole(roleId)
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to delete agent role"
                        )
                    },
                    ifRight = {
                        // If the deleted role was open in the detail page, fall back to the list.
                        if (userSelectedRoleId.value == roleId) {
                            userSelectedRoleId.value = null
                        }
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Cancels any dialog (form or confirmation).
     */
    fun cancelDialog() {
        _dialogState.value = AgentRoleDialogState.None
    }

    private fun saveNewRole(formState: AgentRoleFormState) {
        val validationError = formState.validate()
        if (validationError != null) {
            updateRoleForm { it.withError(validationError) }
            return
        }
        viewModelScope.launch(uiDispatcher) {
            agentRoleRepository.createRole(formState.toCreateRequest())
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to create agent role"
                        )
                        updateRoleForm { it.withError("Error creating agent role: ${error.message}") }
                    },
                    ifRight = { createdRole ->
                        cancelDialog()
                        selectRole(createdRole)
                    }
                )
        }
    }

    private fun saveEditedRole(dialogState: AgentRoleDialogState.EditRole) {
        val formState = dialogState.formState
        val validationError = formState.validate()
        if (validationError != null) {
            updateRoleForm { it.withError(validationError) }
            return
        }
        viewModelScope.launch(uiDispatcher) {
            agentRoleRepository.updateRole(dialogState.role.id, formState.toUpdateRequest())
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to update agent role"
                        )
                        updateRoleForm { it.withError("Error updating agent role: ${error.message}") }
                    },
                    ifRight = { updatedRole ->
                        cancelDialog()
                        selectRole(updatedRole)
                    }
                )
        }
    }

    private fun isChatCapableSettings(settings: ModelSettings): Boolean {
        return settings.modelType == LLMModelType.CHAT || settings.modelType == LLMModelType.RESPONSES
    }
}
