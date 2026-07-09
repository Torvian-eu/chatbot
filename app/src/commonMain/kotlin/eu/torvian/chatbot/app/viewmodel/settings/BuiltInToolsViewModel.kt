package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.BuiltInToolRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.WorkerRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.worker.WorkerDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for the Built-in Tools settings category.
 *
 * This ViewModel lets an administrator pick a registered worker and list/toggle the
 * built-in tools that worker exposes. Tool toggles are persisted through
 * [BuiltInToolRepository.updateBuiltInTool]; on failure the optimistic toggle is rolled
 * back and the user is notified through [NotificationService].
 *
 * @property workerRepository Source of the registered workers used to populate the
 *   worker selection dropdown.
 * @property builtInToolRepository Repository that loads and updates built-in worker tools.
 * @property notificationService Service used to surface load/update failures to the user.
 * @property uiDispatcher Dispatcher used for UI-bound coroutines. Defaults to [Dispatchers.Main].
 */
class BuiltInToolsViewModel(
    private val workerRepository: WorkerRepository,
    private val builtInToolRepository: BuiltInToolRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    /**
     * Reactive stream of registered workers, used to populate the worker selection dropdown.
     */
    val workersState: StateFlow<DataState<RepositoryError, List<WorkerDto>>> = workerRepository.workers

    private val _selectedWorkerId = MutableStateFlow<Long?>(null)

    /**
     * Identifier of the worker whose built-in tools are currently displayed, or null when
     * no worker has been selected yet.
     */
    val selectedWorkerId: StateFlow<Long?> = _selectedWorkerId.asStateFlow()

    /**
     * Reactive stream of built-in tools for the currently selected worker.
     */
    val toolsState: StateFlow<DataState<RepositoryError, List<BuiltInWorkerToolDefinition>>> =
        builtInToolRepository.builtInTools

    /**
     * Selects the worker whose built-in tools should be shown and triggers a load.
     *
     * A null [workerId] clears the selection and resets the tool list to idle.
     *
     * @param workerId Identifier of the worker to select, or null to clear the selection.
     */
    fun selectWorker(workerId: Long?) {
        if (_selectedWorkerId.value == workerId) return
        _selectedWorkerId.value = workerId
        if (workerId == null) return
        loadTools(workerId)
    }

    /**
     * Loads the built-in tools for the given worker and notifies the user on failure.
     *
     * @param workerId Identifier of the worker whose tools should be loaded.
     */
    fun loadTools(workerId: Long) {
        viewModelScope.launch(uiDispatcher) {
            builtInToolRepository.loadTools(workerId)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load built-in tools"
                    )
                }
        }
    }

    /**
     * Toggles the enabled state of a built-in tool.
     *
     * The repository applies an optimistic update on success. If the network update fails,
     * the repository keeps the previous state and the user is notified so the UI reflects the
     * unchanged value.
     *
     * @param tool The tool whose enabled state should be inverted.
     */
    fun toggleToolEnabled(tool: BuiltInWorkerToolDefinition) {
        viewModelScope.launch(uiDispatcher) {
            val updatedTool = tool.copy(isEnabled = !tool.isEnabled)
            builtInToolRepository.updateBuiltInTool(updatedTool)
                .onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to toggle tool"
                    )
                }
        }
    }

    /**
     * Loads the worker list so the selection dropdown has data.
     *
     * Only triggers a load when the current state is idle or errored to avoid redundant
     * network calls.
     */
    fun loadWorkersIfNeeded() {
        val currentState = workerRepository.workers.value
        if (currentState.isIdle || currentState.isError) {
            viewModelScope.launch(uiDispatcher) {
                workerRepository.loadWorkers()
                    .onLeft { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to load workers"
                        )
                    }
            }
        }
    }
}

