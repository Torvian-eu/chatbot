package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerManager
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerOverview
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
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
 * - Communicating with the backend via [LocalMCPServerManager]
 *
 * @property serverManager Manager for orchestrating server operations (test, start, stop, etc.)
 * @property errorNotifier Service for handling and notifying about errors
 * @property uiDispatcher Dispatcher for UI-related coroutines
 */
class LocalMCPServerViewModel(
    private val serverManager: LocalMCPServerManager,
    private val errorNotifier: ErrorNotifier,
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
    val serverOverviews: StateFlow<DataState<RepositoryError, List<LocalMCPServerOverview>>> = serverManager.serverOverviews

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
                    errorNotifier.repositoryError(
                        error = repoError,
                        shortMessage = "Failed to load MCP tools"
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
    fun startEditingServer(server: LocalMCPServer) {
        _dialogState.value = LocalMCPServerDialogState.EditServer(
            server = server,
            formState = LocalMCPServerFormState.fromServer(server)
        )
    }

    /**
     * Opens the confirmation dialog to delete a server.
     */
    fun startDeletingServer(server: LocalMCPServer) {
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
                    if (!form.isValid()) {
                        errorNotifier.genericError(
                            shortMessage = "Please fill in all required fields"
                        )
                        return@launch
                    }

                    _dialogState.update {
                        (it as? LocalMCPServerDialogState.AddNewServer)?.copy(isSaving = true) ?: it
                    }

                    serverManager.createServer(
                        name = form.name,
                        description = form.description.takeIf { it.isNotBlank() },
                        command = form.command,
                        arguments = form.arguments,
                        environmentVariables = form.environmentVariables,
                        workingDirectory = form.workingDirectory.takeIf { it.isNotBlank() },
                        isEnabled = form.isEnabled,
                        autoStartOnEnable = form.autoStartOnEnable,
                        autoStartOnLaunch = form.autoStartOnLaunch,
                        autoStopAfterInactivitySeconds = form.autoStopAfterInactivitySeconds,
                        toolsEnabledByDefault = form.toolsEnabledByDefault
                    ).fold(
                        ifLeft = { error ->
                            _dialogState.update {
                                (it as? LocalMCPServerDialogState.AddNewServer)?.copy(isSaving = false) ?: it
                            }
                            errorNotifier.genericError(
                                shortMessage = "Could not create MCP server: $error"
                            )
                        },
                        ifRight = { server ->
                            _dialogState.value = LocalMCPServerDialogState.None
                            selectServer(server.id)
                        }
                    )
                }

                is LocalMCPServerDialogState.EditServer -> {
                    val form = currentDialogState.formState
                    if (!form.isValid()) {
                        errorNotifier.genericError(
                            shortMessage = "Please fill in all required fields"
                        )
                        return@launch
                    }

                    _dialogState.update {
                        (it as? LocalMCPServerDialogState.EditServer)?.copy(isSaving = true) ?: it
                    }

                    val updatedServer = currentDialogState.server.copy(
                        name = form.name,
                        description = form.description.takeIf { it.isNotBlank() },
                        command = form.command,
                        arguments = form.arguments,
                        environmentVariables = form.environmentVariables,
                        workingDirectory = form.workingDirectory.takeIf { it.isNotBlank() },
                        isEnabled = form.isEnabled,
                        autoStartOnEnable = form.autoStartOnEnable,
                        autoStartOnLaunch = form.autoStartOnLaunch,
                        autoStopAfterInactivitySeconds = form.autoStopAfterInactivitySeconds,
                        toolsEnabledByDefault = form.toolsEnabledByDefault
                    )

                    serverManager.updateServer(updatedServer).fold(
                        ifLeft = { error ->
                            _dialogState.update {
                                (it as? LocalMCPServerDialogState.EditServer)?.copy(isSaving = false) ?: it
                            }
                            errorNotifier.genericError(
                                shortMessage = "Failed to update server: ${error.message}"
                            )
                        },
                        ifRight = {
                            _dialogState.value = LocalMCPServerDialogState.None
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
     * Deletes the specified server.
     */
    fun deleteServer(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            serverManager.deleteServer(serverId).fold(
                ifLeft = { error ->
                    errorNotifier.genericError(
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
     * Tests connection to a server.
     */
    fun testConnection(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            _operationInProgress.value = LocalMCPServerOperation.TestingConnection(serverId)

            serverManager.testConnection(serverId).fold(
                ifLeft = { error ->
                    _operationInProgress.value = null
                    errorNotifier.genericError(
                        shortMessage = "Could not connect to server: $error"
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
     * Refreshes the list of tools from a server.
     */
    fun refreshTools(serverId: Long) {
        viewModelScope.launch(uiDispatcher) {
            _operationInProgress.value = LocalMCPServerOperation.RefreshingTools(serverId)

            serverManager.refreshTools(serverId).fold(
                ifLeft = { error ->
                    _operationInProgress.value = null
                    errorNotifier.genericError(
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
                    errorNotifier.genericError(
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
                    errorNotifier.genericError(
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
    fun toggleServerEnabled(server: LocalMCPServer) {
        viewModelScope.launch(uiDispatcher) {
            val updatedServer = server.copy(isEnabled = !server.isEnabled)
            serverManager.updateServer(updatedServer).onLeft { error ->
                errorNotifier.genericError(
                    shortMessage = "Failed to update server: ${error.message}"
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
        val isSaving: Boolean = false
    ) : LocalMCPServerDialogState()

    data class EditServer(
        val server: LocalMCPServer,
        val formState: LocalMCPServerFormState,
        val isSaving: Boolean = false
    ) : LocalMCPServerDialogState()

    data class DeleteServer(
        val server: LocalMCPServer
    ) : LocalMCPServerDialogState()
}

/**
 * Form state for adding/editing MCP servers.
 */
data class LocalMCPServerFormState(
    val name: String = "",
    val description: String = "",
    val command: String = "",
    val arguments: List<String> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap(),
    val workingDirectory: String = "",
    val isEnabled: Boolean = true,
    val autoStartOnEnable: Boolean = false,
    val autoStartOnLaunch: Boolean = false,
    val autoStopAfterInactivitySeconds: Int? = null,
    val toolsEnabledByDefault: Boolean = false
) {
    fun isValid(): Boolean {
        return name.isNotBlank() && command.isNotBlank()
    }

    companion object {
        fun fromServer(server: LocalMCPServer): LocalMCPServerFormState {
            return LocalMCPServerFormState(
                name = server.name,
                description = server.description ?: "",
                command = server.command,
                arguments = server.arguments,
                environmentVariables = server.environmentVariables,
                workingDirectory = server.workingDirectory ?: "",
                isEnabled = server.isEnabled,
                autoStartOnEnable = server.autoStartOnEnable,
                autoStartOnLaunch = server.autoStartOnLaunch,
                autoStopAfterInactivitySeconds = server.autoStopAfterInactivitySeconds,
                toolsEnabledByDefault = server.toolsEnabledByDefault
            )
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
