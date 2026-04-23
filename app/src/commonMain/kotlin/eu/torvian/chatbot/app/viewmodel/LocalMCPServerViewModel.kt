package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.app.repository.LocalMCPToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerManager
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerOverview
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for configuring Local MCP Servers (US6.4, US6.5).
 *
 * This ViewModel handles:
 * - Loading and displaying a list of configured MCP servers
 * - Managing the state for adding new servers
 * - Managing the state for editing existing server details
 * - Testing connections to MCP servers
 * - Starting/stopping MCP servers
 * - Discovering and refreshing tools from servers
 * - Managing individual tool settings (enable/disable, edit, approval preferences)
 * - Communicating with the backend via [LocalMCPServerManager]
 *
 * @property serverManager Manager for orchestrating server operations (test, start, stop, etc.)
 * @property mcpToolRepository Repository for managing MCP tool definitions
 * @property toolRepository Repository for managing tool approval preferences and general tool operations
 * @property notificationService Service for handling and notifying about notifications
 * @property uiDispatcher Dispatcher for UI-related coroutines
 */
class LocalMCPServerViewModel(
    private val serverManager: LocalMCPServerManager,
    private val mcpToolRepository: LocalMCPToolRepository,
    private val toolRepository: ToolRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Private State Properties ---

    private val userSelectedServerId = MutableStateFlow<Long?>(null)
    private val _dialogState = MutableStateFlow<LocalMCPServerDialogState>(LocalMCPServerDialogState.None)
    private val _operationInProgress = MutableStateFlow<LocalMCPServerOperation?>(null)

    // --- Public State Properties ---

    /**
     * Aggregate status information for all MCP servers.
     * Combines server configs with runtime status (running/stopped/connected, tool count, etc.)
     * Wrapped in DataState to expose loading/error states.
     */
    val serverOverviews: StateFlow<DataState<RepositoryError, List<LocalMCPServerOverview>>> =
        serverManager.serverOverviews.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = DataState.Idle
        )

    /**
     * The currently selected server in the master-detail UI.
     */
    val selectedServerOverview: StateFlow<LocalMCPServerOverview?> = combine(
        serverOverviews,
        userSelectedServerId
    ) { overviewsState, selectedId ->
        when (overviewsState) {
            is DataState.Success -> selectedId?.let { id -> overviewsState.data.find { it.serverConfig.id == id } }
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * Tool approval preferences for tools belonging to the currently selected server.
     * This is used to display a list of active preferences in the server detail panel.
     */
    val selectedServerToolApprovalPreferences: StateFlow<DataState<RepositoryError, List<UserToolApprovalPreference>>> =
        combine(
            selectedServerOverview,
            toolRepository.toolApprovalPreferences
        ) { serverOverview, preferencesState ->
            when (preferencesState) {
                is DataState.Success -> {
                    if (serverOverview == null) {
                        DataState.Success(emptyList())
                    } else {
                        val serverToolIds = serverOverview.tools?.map { it.id }?.toSet() ?: emptySet()
                        val filteredPreferences = preferencesState.data.filter { it.toolDefinitionId in serverToolIds }
                        DataState.Success(filteredPreferences)
                    }
                }

                else -> preferencesState
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), DataState.Idle)

    /**
     * The current dialog state for the MCP servers tab.
     */
    val dialogState: StateFlow<LocalMCPServerDialogState> = _dialogState.asStateFlow()

    /**
     * Current operation in progress (test connection, refresh tools, etc.)
     */
    val operationInProgress: StateFlow<LocalMCPServerOperation?> = _operationInProgress.asStateFlow()

    // --- Public Action Functions ---

    /**
     * Loads all configured MCP servers from the repository.
     */
    fun loadServers(userId: Long) {
        viewModelScope.launch(uiDispatcher) {
            serverManager.loadServers(userId)
                .onLeft { repoError ->
                    // Notify user about repository failure when loading MCP tools
                    notificationService.repositoryError(
                        error = repoError,
                        shortMessage = "Failed to load MCP tools"
                    )
                }

            // Also load tool approval preferences
            toolRepository.loadUserToolApprovalPreferences()
                .onLeft { repoError ->
                    notificationService.repositoryError(
                        error = repoError,
                        shortMessage = "Failed to load tool approval preferences"
                    )
                }
        }
    }

    /**
     * Selects a server (or clears selection when null).
     */
    fun selectServer(serverId: Long?) {
        userSelectedServerId.value = serverId
    }

    /**
     * Opens the dialog to add a new MCP server.
     */
    fun startAddingNewServer() {
        _dialogState.value = LocalMCPServerDialogState.AddNewServer()
    }

    /**
     * Opens the dialog to edit an existing MCP server.
     */
    fun startEditingServer(server: LocalMCPServerDto) {
        _dialogState.value = LocalMCPServerDialogState.EditServer(
            server = server,
            formState = LocalMCPServerFormState.fromServer(server)
        )
    }

    /**
     * Opens the confirmation dialog to delete a server.
     */
    fun startDeletingServer(server: LocalMCPServerDto) {
        _dialogState.value = LocalMCPServerDialogState.DeleteServer(server)
    }

    /**
     * Closes the current dialog and resets form state.
     */
    fun cancelDialog() {
        _dialogState.value = LocalMCPServerDialogState.None
    }

    /**
     * Updates the form state for the current dialog.
     */
    fun updateServerForm(update: (LocalMCPServerFormState) -> LocalMCPServerFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is LocalMCPServerDialogState.AddNewServer -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                is LocalMCPServerDialogState.EditServer -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                else -> dialogState
            }
        }
    }

    /**
     * Saves the current server (create or update based on dialog state).
     */
    fun saveServer() {
        val currentDialogState = _dialogState.value

        viewModelScope.launch(uiDispatcher) {
            when (currentDialogState) {
                is LocalMCPServerDialogState.AddNewServer -> {
                    val form = currentDialogState.formState
                    val validatedForm = form.validate()

                    if (!validatedForm.isValid()) {
                        _dialogState.update {
                            (it as? LocalMCPServerDialogState.AddNewServer)?.copy(formState = validatedForm) ?: it
                        }
                        return@launch
                    }

                    _dialogState.update {
                        (it as? LocalMCPServerDialogState.AddNewServer)?.copy(isSaving = true) ?: it
                    }

                    serverManager.createServer(
                        name = form.name,
                        description = form.description.takeIf { it.isNotBlank() },
                        workerId = form.workerId,
                        command = form.command,
                        arguments = form.arguments,
                        environmentVariables = form.environmentVariables,
                        secretEnvironmentVariables = form.secretEnvironmentVariables,
                        workingDirectory = form.workingDirectory.takeIf { it.isNotBlank() },
                        isEnabled = form.isEnabled,
                        autoStartOnEnable = form.autoStartOnEnable,
                        autoStartOnLaunch = form.autoStartOnLaunch,
                        autoStopAfterInactivitySeconds = form.autoStopAfterInactivitySeconds,
                        toolNamePrefix = form.toolNamePrefix.takeIf { it.isNotBlank() }
                    ).fold(
                        ifLeft = { error ->
                            _dialogState.update {
                                (it as? LocalMCPServerDialogState.AddNewServer)?.copy(isSaving = false) ?: it
                            }
                            notificationService.genericError(
                                shortMessage = "Could not create MCP server: $error"
                            )
                        },
                        ifRight = { server ->
                            _dialogState.value = LocalMCPServerDialogState.None
                            selectServer(server.id)
                            refreshToolsAfterSave(server.id, server.isEnabled)
                        }
                    )
                }

                is LocalMCPServerDialogState.EditServer -> {
                    val form = currentDialogState.formState
                    val validatedForm = form.validate()

                    if (!validatedForm.isValid()) {
                        _dialogState.update {
                            (it as? LocalMCPServerDialogState.EditServer)?.copy(formState = validatedForm) ?: it
                        }
                        return@launch
                    }

                    _dialogState.update {
                        (it as? LocalMCPServerDialogState.EditServer)?.copy(isSaving = true) ?: it
                    }

                    val updatedServer = currentDialogState.server.copy(
                        workerId = form.workerId,
                        name = form.name,
                        description = form.description.takeIf { it.isNotBlank() },
                        command = form.command,
                        arguments = form.arguments,
                        environmentVariables = form.environmentVariables,
                        secretEnvironmentVariables = form.secretEnvironmentVariables,
                        workingDirectory = form.workingDirectory.takeIf { it.isNotBlank() },
                        isEnabled = form.isEnabled,
                        autoStartOnEnable = form.autoStartOnEnable,
                        autoStartOnLaunch = form.autoStartOnLaunch,
                        autoStopAfterInactivitySeconds = form.autoStopAfterInactivitySeconds,
                        toolNamePrefix = form.toolNamePrefix.takeIf { it.isNotBlank() }
                    )

                    serverManager.updateServer(updatedServer).fold(
                        ifLeft = { error ->
                            _dialogState.update {
                                (it as? LocalMCPServerDialogState.EditServer)?.copy(isSaving = false) ?: it
                            }
                            notificationService.genericError(
                                shortMessage = "Failed to update server: ${error.message}"
                            )
                        },
                        ifRight = {
                            _dialogState.value = LocalMCPServerDialogState.None
                            refreshToolsAfterSave(updatedServer.id, updatedServer.isEnabled)
                        }
                    )
                }

                else -> {
                    // No action needed
                }
            }
        }
    }

    /**
     * Runs a best-effort tool refresh after a successful server save.
     *
     * Disabled servers are skipped because they are not expected to be callable.
     */
    private suspend fun refreshToolsAfterSave(serverId: Long, isEnabled: Boolean) {
        if (!isEnabled) return

        serverManager.refreshTools(serverId).onLeft { error ->
            notificationService.genericError(
                shortMessage = "Server saved, but tool refresh failed: ${error.message}"
            )
        }
    }

    /**
     * Deletes the specified server.
     */
    fun deleteServer(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            serverManager.deleteServer(serverId).fold(
                ifLeft = { error ->
                    notificationService.genericError(
                        shortMessage = "Failed to delete server: ${error.message}"
                    )
                },
                ifRight = {
                    _dialogState.value = LocalMCPServerDialogState.None
                    if (userSelectedServerId.value == serverId) {
                        userSelectedServerId.value = null
                    }
                }
            )
        }
    }

    /**
     * Tests the server connection using the current form state (unsaved config).
     * Works for both AddNewServer and EditServer dialogs.
     * Uses testConnectionForNewServer so the pending (unsaved) config is always tested.
     */
    fun testServerInDialog() {
        val currentDialogState = _dialogState.value
        val form = when (currentDialogState) {
            is LocalMCPServerDialogState.AddNewServer -> currentDialogState.formState
            is LocalMCPServerDialogState.EditServer -> currentDialogState.formState
            else -> return
        }

        viewModelScope.launch(uiDispatcher) {
            // Set loading state, clear previous result
            _dialogState.update {
                when (it) {
                    is LocalMCPServerDialogState.AddNewServer ->
                        it.copy(isTesting = true, testResult = null)
                    is LocalMCPServerDialogState.EditServer ->
                        it.copy(isTesting = true, testResult = null)
                    else -> it
                }
            }

            val result = serverManager.testConnectionForNewServer(
                workerId = form.workerId,
                name = form.name.ifBlank { "Test" },
                command = form.command,
                arguments = form.arguments,
                environmentVariables = form.environmentVariables,
                secretEnvironmentVariables = form.secretEnvironmentVariables,
                workingDirectory = form.workingDirectory.takeIf { it.isNotBlank() }
            )

            val testResult = result.fold(
                ifLeft = { error -> DialogTestResult.Failure(error.message) },
                ifRight = { toolCount -> DialogTestResult.Success(toolCount) }
            )

            _dialogState.update {
                when (it) {
                    is LocalMCPServerDialogState.AddNewServer ->
                        it.copy(isTesting = false, testResult = testResult)
                    is LocalMCPServerDialogState.EditServer ->
                        it.copy(isTesting = false, testResult = testResult)
                    else -> it
                }
            }
        }
    }

    /**
     * Tests connection to a server.
     */
    fun testConnection(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            _operationInProgress.value = LocalMCPServerOperation.TestingConnection(serverId)

            serverManager.testConnection(serverId).fold(
                ifLeft = { error ->
                    _operationInProgress.value = null
                    notificationService.genericError(
                        shortMessage = "Could not connect to server: $error"
                    )
                },
                ifRight = { toolCount ->
                    _operationInProgress.value = null
                    notificationService.genericSuccess(
                        shortMessage = "Connected â€” $toolCount tool(s) discovered"
                    )
                }
            )
        }
    }

    /**
     * Refreshes the list of tools from a server.
     */
    fun refreshTools(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            _operationInProgress.value = LocalMCPServerOperation.RefreshingTools(serverId)

            serverManager.refreshTools(serverId).fold(
                ifLeft = { error ->
                    _operationInProgress.value = null
                    notificationService.genericError(
                        shortMessage = "Could not refresh tools: $error"
                    )
                },
                ifRight = {
                    _operationInProgress.value = null
                    // Success - could add a success notification system later
                }
            )
        }
    }

    /**
     * Starts a server process.
     */
    fun startServer(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            _operationInProgress.value = LocalMCPServerOperation.StartingServer(serverId)

            serverManager.startServer(serverId).fold(
                ifLeft = { error ->
                    _operationInProgress.value = null
                    notificationService.genericError(
                        shortMessage = "Could not start server: $error"
                    )
                },
                ifRight = {
                    _operationInProgress.value = null
                    // Success - could add a success notification system later
                }
            )
        }
    }

    /**
     * Stops a server process.
     */
    fun stopServer(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            _operationInProgress.value = LocalMCPServerOperation.StoppingServer(serverId)

            serverManager.stopServer(serverId).fold(
                ifLeft = { error ->
                    _operationInProgress.value = null
                    notificationService.genericError(
                        shortMessage = "Could not stop server: $error"
                    )
                },
                ifRight = {
                    _operationInProgress.value = null
                    // Success - could add a success notification system later
                }
            )
        }
    }

    /**
     * Toggles the global enabled state of a server.
     */
    fun toggleServerEnabled(server: LocalMCPServerDto) {
        viewModelScope.launch(uiDispatcher) {
            val updatedServer = server.copy(isEnabled = !server.isEnabled)
            serverManager.updateServer(updatedServer).onLeft { error ->
                notificationService.genericError(
                    shortMessage = "Failed to update server: ${error.message}"
                )
            }
        }
    }

    /**
     * Toggles the enabled state of a specific tool.
     * Executes asynchronously without blocking UI.
     */
    fun toggleToolEnabled(tool: LocalMCPToolDefinition) {
        viewModelScope.launch(uiDispatcher) {
            val updatedTool = tool.copy(isEnabled = !tool.isEnabled)
            mcpToolRepository.updateMCPTool(updatedTool).onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to toggle tool"
                )
            }
        }
    }

    /**
     * Opens the dialog to edit a tool.
     * Loads the approval preference asynchronously in the background.
     * If preference loading fails, the dialog is not opened and an error is shown.
     */
    fun startEditingTool(tool: LocalMCPToolDefinition) {
        viewModelScope.launch(uiDispatcher) {
            val approvalPreference =
                toolRepository.toolApprovalPreferences.value.dataOrNull?.find { it.toolDefinitionId == tool.id }
            _dialogState.value = LocalMCPServerDialogState.EditTool(
                tool = tool,
                approvalPreference = approvalPreference,
                formState = LocalMCPToolFormState(
                    name = tool.name,
                    isEnabled = tool.isEnabled,
                    approvalPreferenceActive = approvalPreference != null,
                    autoApprove = approvalPreference?.autoApprove ?: true,
                    conditions = approvalPreference?.conditions,
                    denialReason = approvalPreference?.denialReason
                ),
            )
        }
    }

    /**
     * Saves changes to a tool and its approval preference.
     */
    fun saveTool() {
        val currentDialogState = _dialogState.value

        if (currentDialogState !is LocalMCPServerDialogState.EditTool) {
            return
        }

        val form = currentDialogState.formState
        val toolId = currentDialogState.tool.id

        viewModelScope.launch(uiDispatcher) {
            _dialogState.update {
                (it as? LocalMCPServerDialogState.EditTool)?.copy(isSaving = true) ?: it
            }

            // Step 1: Update the tool settings
            val updatedTool = currentDialogState.tool.copy(
                name = form.name,
                isEnabled = form.isEnabled
            )

            mcpToolRepository.updateMCPTool(updatedTool)
                .onLeft { error ->
                    _dialogState.update {
                        (it as? LocalMCPServerDialogState.EditTool)?.copy(isSaving = false) ?: it
                    }
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to update tool"
                    )
                    return@launch
                }

            // Step 2: Update the approval preference
            if (form.hasApprovalPreferenceChanged(currentDialogState.approvalPreference)) {
                if (form.approvalPreferenceActive) {
                    toolRepository.setToolApprovalPreference(
                        toolDefinitionId = toolId,
                        autoApprove = form.autoApprove,
                        conditions = form.conditions,
                        denialReason = form.denialReason
                    ).onLeft { error ->
                        _dialogState.update {
                            (it as? LocalMCPServerDialogState.EditTool)?.copy(isSaving = false) ?: it
                        }
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to save approval preference"
                        )
                        return@launch
                    }
                } else {
                    toolRepository.deleteToolApprovalPreference(toolId)
                        .onLeft { error ->
                            _dialogState.update {
                                (it as? LocalMCPServerDialogState.EditTool)?.copy(isSaving = false) ?: it
                            }
                            notificationService.repositoryError(
                                error = error,
                                shortMessage = "Failed to delete approval preference"
                            )
                            return@launch
                        }
                }
                // All operations completed successfully
                _dialogState.value = LocalMCPServerDialogState.None
            }
        }
    }

    /**
     * Updates the tool form state for the current dialog.
     */
    fun updateToolForm(update: (LocalMCPToolFormState) -> LocalMCPToolFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is LocalMCPServerDialogState.EditTool -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                else -> dialogState
            }
        }
    }

    /**
     * Enables all tools for a specific server.
     * Executes asynchronously without blocking UI.
     * Uses efficient batch update endpoint.
     */
    fun enableAllTools(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            val overviewsState = serverOverviews.value
            if (overviewsState !is DataState.Success) return@launch

            val serverOverview = overviewsState.data.find { it.serverId == serverId }
            val tools = serverOverview?.tools ?: return@launch

            val toolsToEnable = tools.filter { !it.isEnabled }
            if (toolsToEnable.isEmpty()) return@launch

            val updatedTools = toolsToEnable.map { it.copy(isEnabled = true) }
            mcpToolRepository.batchUpdateMCPTools(serverId, updatedTools).onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to enable all tools"
                )
            }
        }
    }

    /**
     * Disables all tools for a specific server.
     */
    fun disableAllTools(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            val overviewsState = serverOverviews.value
            if (overviewsState !is DataState.Success) return@launch

            val serverOverview = overviewsState.data.find { it.serverId == serverId }
            val tools = serverOverview?.tools ?: return@launch

            val toolsToDisable = tools.filter { it.isEnabled }
            if (toolsToDisable.isEmpty()) return@launch

            val updatedTools = toolsToDisable.map { it.copy(isEnabled = false) }
            mcpToolRepository.batchUpdateMCPTools(serverId, updatedTools).onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to disable all tools"
                )
            }
        }
    }

    /**
     * Deletes an approval preference for a specific tool.
     * Used from the server detail panel overview.
     */
    fun deleteToolApprovalPreference(toolDefinitionId: Long) {
        viewModelScope.launch(uiDispatcher) {
            toolRepository.deleteToolApprovalPreference(toolDefinitionId)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to delete approval preference"
                    )
                }
        }
    }
}

/**
 * Represents the state of dialogs in the MCP servers tab.
 */
sealed class LocalMCPServerDialogState {
    data object None : LocalMCPServerDialogState()

    data class AddNewServer(
        val formState: LocalMCPServerFormState = LocalMCPServerFormState(),
        val isSaving: Boolean = false,
        val isTesting: Boolean = false,
        val testResult: DialogTestResult? = null
    ) : LocalMCPServerDialogState()

    data class EditServer(
        val server: LocalMCPServerDto,
        val formState: LocalMCPServerFormState,
        val isSaving: Boolean = false,
        val isTesting: Boolean = false,
        val testResult: DialogTestResult? = null
    ) : LocalMCPServerDialogState()

    data class DeleteServer(
        val server: LocalMCPServerDto
    ) : LocalMCPServerDialogState()

    data class EditTool(
        val tool: LocalMCPToolDefinition,
        val approvalPreference: UserToolApprovalPreference?,
        val formState: LocalMCPToolFormState,
        val isSaving: Boolean = false
    ) : LocalMCPServerDialogState()
}

/**
 * Result of a "Test Server" action triggered from within a config dialog.
 */
sealed class DialogTestResult {
    data class Success(val toolCount: Int) : DialogTestResult()
    data class Failure(val message: String) : DialogTestResult()
}

/**
 * Form state for adding/editing MCP servers.
 *
 * @property workerId Worker assignment ID used by server-side MCP routing.
 * @property environmentVariables Non-secret environment variables.
 * @property secretEnvironmentVariables Secret environment variables.
 * @property workerIdError Validation error shown when worker ID is missing or invalid.
 */
data class LocalMCPServerFormState(
    val workerId: Long = 0L,
    val name: String = "",
    val description: String = "",
    val command: String = "",
    val arguments: List<String> = emptyList(),
    val environmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
    val secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
    val workingDirectory: String = "",
    val isEnabled: Boolean = true,
    val autoStartOnEnable: Boolean = false,
    val autoStartOnLaunch: Boolean = false,
    val autoStopAfterInactivitySeconds: Int? = null,
    val toolNamePrefix: String = "",
    val workerIdError: String? = null,
    val nameError: String? = null,
    val commandError: String? = null,
    val fullCommand: String = ""
) {
    /**
     * Returns true if the form has no validation errors.
     */
    fun isValid(): Boolean {
        return workerIdError == null && nameError == null && commandError == null && workerId > 0 && name.isNotBlank() && command.isNotBlank()
    }

    /**
     * Validates the form and returns a new state with error messages if validation fails.
     * Returns the same state if already valid.
     */
    fun validate(): LocalMCPServerFormState {
        val newNameError = when {
            name.isBlank() -> "Name is required"
            else -> null
        }
        val newWorkerIdError = when {
            workerId <= 0L -> "Worker ID is required"
            else -> null
        }
        val newCommandError = when {
            command.isBlank() -> "Command is required"
            else -> null
        }

        return if (newWorkerIdError != null || newNameError != null || newCommandError != null) {
            copy(workerIdError = newWorkerIdError, nameError = newNameError, commandError = newCommandError)
        } else {
            this
        }
    }

    /**
     * Parses [fullCommand] into [command] + [arguments] using shell-like tokenisation.
     *
     * Splits on whitespace, respecting single- and double-quoted spans (quotes are stripped).
     * The first token becomes [command]; remaining tokens become [arguments].
     * [fullCommand] is cleared and [commandError] is reset after a successful parse.
     * Returns this unchanged if [fullCommand] is blank or produces no tokens.
     */
    fun parseFullCommand(): LocalMCPServerFormState {
        val tokens = tokenizeCommand(fullCommand)
        if (tokens.isEmpty()) return this
        return copy(
            command = tokens.first(),
            arguments = tokens.drop(1),
            fullCommand = "",
            commandError = null
        )
    }

    companion object {
        fun fromServer(server: LocalMCPServerDto): LocalMCPServerFormState {
            return LocalMCPServerFormState(
                workerId = server.workerId,
                name = server.name,
                description = server.description ?: "",
                command = server.command,
                arguments = server.arguments,
                environmentVariables = server.environmentVariables,
                secretEnvironmentVariables = server.secretEnvironmentVariables,
                workingDirectory = server.workingDirectory ?: "",
                isEnabled = server.isEnabled,
                autoStartOnEnable = server.autoStartOnEnable,
                autoStartOnLaunch = server.autoStartOnLaunch,
                autoStopAfterInactivitySeconds = server.autoStopAfterInactivitySeconds,
                toolNamePrefix = server.toolNamePrefix ?: ""
            )
        }
    }
}

/**
 * Shell-like tokeniser for a command string.
 *
 * Splits on whitespace while respecting single-quoted ('...') and double-quoted ("...")
 * spans. Quotes are stripped from the result. Unclosed quotes consume the remainder of
 * the input as a single token (graceful fallback). Leading/trailing whitespace is ignored.
 *
 * Examples:
 * - `"npx -y server /path"` â†’ `["npx", "-y", "server", "/path"]`
 * - `"docker run -e KEY=\"hello world\" image"` â†’ `["docker", "run", "-e", "KEY=hello world", "image"]`
 * - `"java -jar \"my server.jar\""` â†’ `["java", "-jar", "my server.jar"]`
 */
internal fun tokenizeCommand(input: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    val s = input.trim()

    while (i < s.length) {
        when {
            // Start of a double-quoted span
            s[i] == '"' -> {
                val end = s.indexOf('"', i + 1)
                if (end == -1) {
                    // Unclosed quote â€” consume the rest
                    current.append(s.substring(i + 1))
                    i = s.length
                } else {
                    current.append(s.substring(i + 1, end))
                    i = end + 1
                }
            }
            // Start of a single-quoted span
            s[i] == '\'' -> {
                val end = s.indexOf('\'', i + 1)
                if (end == -1) {
                    current.append(s.substring(i + 1))
                    i = s.length
                } else {
                    current.append(s.substring(i + 1, end))
                    i = end + 1
                }
            }
            // Whitespace â€” flush current token if any
            s[i].isWhitespace() -> {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
                i++
            }
            // Regular character
            else -> {
                current.append(s[i])
                i++
            }
        }
    }

    if (current.isNotEmpty()) tokens.add(current.toString())
    return tokens
}

/**
 * Form state for editing MCP tools.
 */
data class LocalMCPToolFormState(
    val name: String = "",
    val isEnabled: Boolean = true,
    val approvalPreferenceActive: Boolean = false,
    val autoApprove: Boolean = true,
    val conditions: String? = null,
    val denialReason: String? = null
) {
    fun isValid(): Boolean = name.isNotBlank()

    fun hasApprovalPreferenceChanged(currentPreference: UserToolApprovalPreference?): Boolean {
        if (approvalPreferenceActive != (currentPreference != null)) return true
        return if (approvalPreferenceActive && currentPreference != null) {
            autoApprove != (currentPreference.autoApprove) ||
                    conditions != currentPreference.conditions ||
                    denialReason != currentPreference.denialReason
        } else {
            false
        }
    }
}

/**
 * Represents an operation in progress on an MCP server.
 */
sealed class LocalMCPServerOperation {
    data class TestingConnection(val serverId: Long) : LocalMCPServerOperation()
    data class RefreshingTools(val serverId: Long) : LocalMCPServerOperation()
    data class StartingServer(val serverId: Long) : LocalMCPServerOperation()
    data class StoppingServer(val serverId: Long) : LocalMCPServerOperation()
}
