package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.worker.WorkerDto

/**
 * UI state for the Built-in Tools tab.
 *
 * @property workersState Reactive state of the registered workers used to populate the
 *   worker selection dropdown.
 * @property selectedWorkerId Identifier of the worker whose built-in tools are displayed, or
 *   null when no worker is selected yet.
 * @property toolsState Reactive state of the built-in tools for the selected worker.
 */
data class BuiltInToolsTabState(
    val workersState: DataState<RepositoryError, List<WorkerDto>>,
    val selectedWorkerId: Long?,
    val toolsState: DataState<RepositoryError, List<BuiltInWorkerToolDefinition>>
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
}

