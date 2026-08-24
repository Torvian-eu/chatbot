package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference

/**
 * UI state for the Server Built-In Tools tab.
 *
 * @property toolsState Reactive state of the current user's server built-in tool definitions.
 * @property approvalPreferencesState Reactive state of the user's tool approval preferences,
 *   used to render the auto-approval mode of each tool row.
 * @property resetInProgress Whether a reset-to-defaults operation is currently in flight, used
 *   to disable the reset control and show progress.
 * @property toolNamePrefix The user's stored server built-in tool name prefix (`null` = no stored
 *   preference, so the server default `"chatbot-"` applies; `""` = explicitly no prefix).
 */
data class ServerBuiltInToolsTabState(
    val toolsState: DataState<RepositoryError, List<ServerBuiltInToolDefinition>>,
    val approvalPreferencesState: DataState<RepositoryError, List<UserToolApprovalPreference>>,
    val resetInProgress: Boolean,
    val toolNamePrefix: String?
)

/**
 * Actions available in the Server Built-In Tools tab.
 */
interface ServerBuiltInToolsTabActions {
    /**
     * Reloads the current user's server built-in tools.
     */
    fun onLoadTools()

    /**
     * Toggles the enabled state of the given server built-in tool.
     */
    fun onToggleToolEnabled(tool: ServerBuiltInToolDefinition)

    /**
     * Persists an edited server built-in tool definition.
     *
     * @param tool The full server built-in tool definition containing the edited description or
     *   schema.
     */
    fun onUpdateTool(tool: ServerBuiltInToolDefinition)

    /**
     * Sets the auto-approval mode for the given tool definition.
     *
     * @param toolDefinitionId The tool definition identifier to configure.
     * @param autoApprove When true the tool is auto-approved; when false the tool is auto-denied.
     */
    fun onSetApprovalPreference(toolDefinitionId: Long, autoApprove: Boolean)

    /**
     * Removes the auto-approval preference for the given tool, reverting it to the default
     * behaviour where no stored preference exists.
     *
     * @param toolDefinitionId The tool definition identifier whose preference should be cleared.
     */
    fun onClearApprovalPreference(toolDefinitionId: Long)

    /**
     * Resets the current user's server built-in tools to the catalog defaults.
     */
    fun onResetToDefaults()

    /**
     * Stores the user's server built-in tool name prefix (blank = no prefix).
     *
     * @param prefix The requested prefix; blank clears the prefix (canonical tool names).
     */
    fun onSaveToolNamePrefix(prefix: String)

    /**
     * Resets the user's server built-in tool name prefix to the server default (`"chatbot-"`).
     */
    fun onResetToolNamePrefix()
}
