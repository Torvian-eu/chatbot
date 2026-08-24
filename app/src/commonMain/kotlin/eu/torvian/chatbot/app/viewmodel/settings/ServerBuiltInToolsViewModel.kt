package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ServerBuiltInToolRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
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
 * and lets the user toggle their enabled state, edit their description/input schema, configure
 * auto-approval, and set the per-user tool name prefix (stored as a GLOBAL preference; the server
 * renames the persisted public names atomically). Tool toggles are persisted through
 * [ServerBuiltInToolRepository.updateServerBuiltInTool]; on failure the optimistic update is rolled
 * back and the user is notified through [NotificationService]. It also loads and mutates the user's
 * per-tool auto-approval preferences via [ToolRepository], reusing the generic approval-preference
 * endpoints.
 *
 * @property serverBuiltInToolRepository Repository that loads and updates per-user server built-in
 *            tools.
 * @property toolRepository Repository that loads and stores the user's tool approval preferences.
 * @property userPreferenceRepository Repository exposing the user's stored tool name prefix and
 *            providing the set/reset operations for it.
 * @property notificationService Service used to surface load/update failures to the user.
 * @property uiDispatcher Dispatcher used for UI-bound coroutines. Defaults to [Dispatchers.Main].
 */
class ServerBuiltInToolsViewModel(
    private val serverBuiltInToolRepository: ServerBuiltInToolRepository,
    private val toolRepository: ToolRepository,
    private val userPreferenceRepository: UserPreferenceRepository,
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

    /**
     * Reactive stream of the user's stored server built-in tool name prefix (`null` = no stored
     * preference; the server default `"chatbot-"` applies and is surfaced as a placeholder hint).
     */
    val toolNamePrefix: StateFlow<String?> = userPreferenceRepository.serverBuiltInToolNamePrefix

    private val _resetInProgress = MutableStateFlow(false)

    /**
     * Whether a reset-to-defaults operation is currently in flight. Used by the UI to disable the
     * reset control and show progress.
     */
    val resetInProgress: StateFlow<Boolean> = _resetInProgress.asStateFlow()

    /**
     * Loads the current user's server built-in tools, approval preferences, and the stored tool
     * name prefix, notifying the user on failure. Tool-list loading is skipped when the data is
     * already loaded to avoid redundant network calls; the prefix preference is cheap and always
     * refreshed so the input shows the authoritative value.
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
            userPreferenceRepository.syncPreferences()
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load tool name prefix"
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

    /**
     * Stores the user's server built-in tool name prefix (blank = no prefix).
     *
     * The server renames the user's persisted tool names to the new prefix atomically with the
     * preference write; after success the tool list is reloaded so the rows show the new public
     * names. The user is notified on failure. The operation runs fire-and-forget (the prefix dialog
     * closes on confirm, mirroring the other dialogs in the tab), so no in-flight UI state is
     * tracked here.
     *
     * @param prefix The requested prefix; blank clears the prefix (canonical tool names).
     */
    fun saveToolNamePrefix(prefix: String) {
        viewModelScope.launch(uiDispatcher) {
            userPreferenceRepository.setServerBuiltInToolNamePrefix(prefix)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to save tool name prefix"
                    )
                }
                .onRight {
                    // The rename changed the persisted public names; refresh the list so the
                    // rows show the new prefixed names.
                    serverBuiltInToolRepository.loadTools()
                        .onLeft { error ->
                            notificationService.repositoryError(
                                error = error,
                                shortMessage = "Failed to refresh tools after prefix change"
                            )
                        }
                }
        }
    }

    /**
     * Resets the user's server built-in tool name prefix to the server default (`"chatbot-"`).
     *
     * Deletes the global preference row; the server renames the user's tools back to the default
     * prefix atomically. After success the tool list is reloaded so the rows show the default
     * public names. The user is notified on failure. The operation runs fire-and-forget (the prefix
     * dialog closes on confirm, mirroring the other dialogs in the tab), so no in-flight UI state
     * is tracked here.
     */
    fun resetToolNamePrefix() {
        viewModelScope.launch(uiDispatcher) {
            userPreferenceRepository.resetServerBuiltInToolNamePrefix()
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to reset tool name prefix"
                    )
                }
                .onRight {
                    serverBuiltInToolRepository.loadTools()
                        .onLeft { error ->
                            notificationService.repositoryError(
                                error = error,
                                shortMessage = "Failed to refresh tools after prefix reset"
                            )
                        }
                }
        }
    }
}
