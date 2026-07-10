package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import eu.torvian.chatbot.common.models.worker.WorkerDto

/**
 * UI state for the Built-in Tools tab.
 *
 * @property workersState Reactive state of the registered workers used to populate the
 *   worker selection dropdown.
 * @property selectedWorkerId Identifier of the worker whose built-in tools are displayed, or
 *   null when no worker is selected yet.
 * @property toolsState Reactive state of the built-in tools for the selected worker.
 * @property approvalPreferencesState Reactive state of the user's tool approval preferences,
 *   used to render the auto-approval mode of each tool row.
 */
data class BuiltInToolsTabState(
    val workersState: DataState<RepositoryError, List<WorkerDto>>,
    val selectedWorkerId: Long?,
    val toolsState: DataState<RepositoryError, List<BuiltInWorkerToolDefinition>>,
    val approvalPreferencesState: DataState<RepositoryError, List<UserToolApprovalPreference>>
)

/**
 * Actions available in the Built-in Tools tab.
 */
interface BuiltInToolsTabActions {
    /**
     * Selects the worker whose built-in tools should be shown, or clears the selection when null.
     */
    fun onSelectWorker(workerId: Long?)

    /**
     * Reloads the built-in tools for the given worker.
     */
    fun onLoadTools(workerId: Long)

    /**
     * Toggles the enabled state of the given built-in tool.
     */
    fun onToggleToolEnabled(tool: BuiltInWorkerToolDefinition)

    /**
     * Persists an edited built-in tool definition.
     */
    fun onUpdateTool(tool: BuiltInWorkerToolDefinition)

    /**
     * Sets the auto-approval mode for the given tool definition.
     *
     * @param toolDefinitionId The tool definition identifier to configure.
     * @param autoApprove When true the tool is auto-approved; when false the tool is auto-denied.
     */
    fun onSetApprovalPreference(toolDefinitionId: Long, autoApprove: Boolean)

    /**
     * Removes the auto-approval preference for the given tool, reverting it to manual
     * ("Requires User Approval") approval where the user is prompted on every call.
     *
     * @param toolDefinitionId The tool definition identifier whose preference should be cleared.
     */
    fun onClearApprovalPreference(toolDefinitionId: Long)
}
