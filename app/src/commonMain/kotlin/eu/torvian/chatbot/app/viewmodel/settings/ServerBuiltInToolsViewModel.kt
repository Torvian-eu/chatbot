package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ServerBuiltInToolRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for the Server Built-In Tools settings category.
 *
 * This ViewModel lists the current user's per-user server built-in tools (e.g. `list_agent_roles`)
 * and lets the user toggle their enabled state, edit their description/input schema, and configure
 * auto-approval. Tool toggles are persisted through
 * [ServerBuiltInToolRepository.updateServerBuiltInTool]; on failure the optimistic update is rolled
 * back and the user is notified through [NotificationService]. It also loads and mutates the user's
 * per-tool auto-approval preferences via [ToolRepository], reusing the generic approval-preference
 * endpoints.
 *
 * @property serverBuiltInToolRepository Repository that loads and updates per-user server built-in
 *            tools.
 * @property toolRepository Repository that loads and stores the user's tool approval preferences.
 * @property notificationService Service used to surface load/update failures to the user.
 * @property uiDispatcher Dispatcher used for UI-bound coroutines. Defaults to [Dispatchers.Main].
 */
class ServerBuiltInToolsViewModel(
    private val serverBuiltInToolRepository: ServerBuiltInToolRepository,
    private val toolRepository: ToolRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    /**
     * Reactive stream of the current user's server built-in tool definitions.
     */
    val toolsState: StateFlow<DataState<RepositoryError, List<ServerBuiltInToolDefinition>>> =
        serverBuiltInToolRepository.serverBuiltInTools

    /**
     * Reactive stream of the current user's tool approval preferences, used to render the
     * auto-approval state of each server built-in tool row.
     */
    val approvalPreferencesState: StateFlow<DataState<RepositoryError, List<UserToolApprovalPreference>>> =
        toolRepository.toolApprovalPreferences

    private val _resetInProgress = MutableStateFlow(false)

    /**
     * Whether a reset-to-defaults operation is currently in flight. Used by the UI to disable the
     * reset control and show progress.
     */
    val resetInProgress: StateFlow<Boolean> = _resetInProgress.asStateFlow()

    /**
     * Loads the current user's server built-in tools and approval preferences, notifying the user on
     * failure. No-op when the tools are already loaded to avoid redundant network calls.
     */
    fun loadToolsIfNeeded() {
        val currentState = serverBuiltInToolRepository.serverBuiltInTools.value
        if (!currentState.isIdle && !currentState.isError) return

        viewModelScope.launch(uiDispatcher) {
            serverBuiltInToolRepository.loadTools()
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load server built-in tools"
                    )
                }
            toolRepository.loadUserToolApprovalPreferences()
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load approval preferences"
                    )
                }
        }
    }

    /**
     * Persists an edited server built-in tool definition.
     *
     * The repository updates its cached definition after the server accepts the change. On failure,
     * the existing definition remains visible and the user receives an error notification.
     *
     * @param tool The full definition containing the edited description or input schema.
     */
    fun updateTool(tool: ServerBuiltInToolDefinition) {
        viewModelScope.launch(uiDispatcher) {
            serverBuiltInToolRepository.updateServerBuiltInTool(tool)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to save tool"
                    )
                }
        }
    }

    /**
     * Toggles the enabled state of a server built-in tool.
     *
     * The repository applies an optimistic update on success. If the network update fails, the
     * repository keeps the previous state and the user is notified so the UI reflects the unchanged
     * value.
     *
     * @param tool The tool whose enabled state should be inverted.
     */
    fun toggleToolEnabled(tool: ServerBuiltInToolDefinition) {
        viewModelScope.launch(uiDispatcher) {
            val updatedTool = tool.copy(isEnabled = !tool.isEnabled)
            serverBuiltInToolRepository.updateServerBuiltInTool(updatedTool)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to toggle tool"
                    )
                }
        }
    }

    /**
     * Sets or updates the auto-approval preference for a server built-in tool.
     *
     * @param toolDefinitionId The tool definition identifier to configure.
     * @param autoApprove When true the tool is auto-approved; when false the tool is auto-denied.
     */
    fun setApprovalPreference(toolDefinitionId: Long, autoApprove: Boolean) {
        viewModelScope.launch(uiDispatcher) {
            toolRepository.setToolApprovalPreference(
                toolDefinitionId = toolDefinitionId,
                autoApprove = autoApprove
            ).onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to save approval preference"
                )
            }
        }
    }

    /**
     * Removes the auto-approval preference for a server built-in tool, reverting it to the default
     * behaviour where the approval dialog is shown (or the client-side default is applied).
     *
     * @param toolDefinitionId The tool definition identifier whose preference should be cleared.
     */
    fun clearApprovalPreference(toolDefinitionId: Long) {
        viewModelScope.launch(uiDispatcher) {
            toolRepository.deleteToolApprovalPreference(toolDefinitionId = toolDefinitionId)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to clear approval preference"
                    )
                }
        }
    }

    /**
     * Resets the current user's server built-in tools to the catalog defaults.
     *
     * Reconciles the user's server built-in tools with the server catalog (missing tools are
     * created, existing tools repaired) while preserving enabled/disabled choices and approval
     * preferences. The user is notified on failure. The in-flight flag is cleared in a `finally`
     * block so a cancelled coroutine (e.g. the ViewModel scope being torn down) can never leave the
     * UI stuck in the "resetting" state.
     */
    fun resetToDefaults() {
        viewModelScope.launch(uiDispatcher) {
            _resetInProgress.value = true
            try {
                serverBuiltInToolRepository.resetToDefaults()
                    .onLeft { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to reset server built-in tools"
                        )
                    }
            } finally {
                _resetInProgress.value = false
            }
        }
    }
}
