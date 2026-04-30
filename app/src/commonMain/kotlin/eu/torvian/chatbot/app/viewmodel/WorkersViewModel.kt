package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.WorkersDialogState
import eu.torvian.chatbot.app.domain.contracts.WorkersFormState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.WorkerRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.worker.WorkerDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for managing Workers.
 *
 * This ViewModel handles:
 * - Loading and displaying a list of registered workers.
 * - Managing the state for editing worker details.
 * - Managing the state for deleting workers.
 * - Communicating with the backend via [WorkerRepository].
 *
 * @constructor
 * @param workerRepository The repository for worker-related operations.
 * @param notificationService Service for notifications and error handling.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property workersState The state of the list of all workers.
 * @property dialogState The current dialog state for the workers tab.
 */
class WorkersViewModel(
    private val workerRepository: WorkerRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Private State Properties ---

    private val _dialogState = MutableStateFlow<WorkersDialogState>(WorkersDialogState.None)

    // --- Public State Properties ---

    /**
     * The state of the list of all workers.
     * Includes loading, success with data, or error states.
     */
    val workersState: StateFlow<DataState<RepositoryError, List<WorkerDto>>> =
        workerRepository.workers

    /**
     * The current dialog state for the workers tab.
     * Manages which dialog (if any) should be displayed and contains dialog-specific form state.
     */
    val dialogState: StateFlow<WorkersDialogState> = _dialogState.asStateFlow()

    // --- Public Action Functions ---

    /**
     * Loads all workers from the backend.
     *
     * With the repository pattern, this method triggers loading in the repository.
     * The reactive data streams will automatically update the UI state.
     */
    fun loadWorkers() {
        viewModelScope.launch(uiDispatcher) {
            workerRepository.loadWorkers()
                .mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load workers"
                    )
                }
        }
    }

    /**
     * Initiates the editing process for an existing worker.
     *
     * @param worker The [WorkerDto] to be edited.
     */
    fun startEditingWorker(worker: WorkerDto) {
        _dialogState.value = WorkersDialogState.EditWorker(
            worker = worker,
            formState = WorkersFormState.fromWorker(worker)
        )
    }

    /**
     * Initiates the deletion process for a worker by showing the confirmation dialog.
     *
     * @param worker The [WorkerDto] to be deleted.
     */
    fun startDeletingWorker(worker: WorkerDto) {
        _dialogState.value = WorkersDialogState.DeleteWorker(worker)
    }

    /**
     * Updates any field in the worker form using a lambda function.
     */
    fun updateWorkerForm(update: (WorkersFormState) -> WorkersFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is WorkersDialogState.EditWorker -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                else -> dialogState // No change for other states
            }
        }
    }

    /**
     * Saves the worker form data (edit mode).
     */
    fun saveWorker() {
        when (val dialogState = _dialogState.value) {
            is WorkersDialogState.EditWorker -> saveEditedWorker(dialogState)
            else -> return
        }
    }

    /**
     * Deletes a specific worker.
     *
     * @param workerId The ID of the worker to delete.
     */
    fun deleteWorker(workerId: Long) {
        viewModelScope.launch(uiDispatcher) {
            workerRepository.deleteWorker(workerId)
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to delete worker"
                        )
                    },
                    ifRight = {
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Closes any open dialog by setting state to None.
     */
    fun cancelDialog() {
        _dialogState.value = WorkersDialogState.None
    }

    // --- Private Helper Functions ---

    /**
     * Saves the edited worker details.
     */
    private fun saveEditedWorker(dialogState: WorkersDialogState.EditWorker) {
        val form = dialogState.formState
        val originalWorker = dialogState.worker

        // Validate the form
        val validationError = form.validate()
        if (validationError != null) {
            updateWorkerForm { it.withError(validationError) }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            val scopes = form.allowedScopes
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            workerRepository.updateWorker(
                id = originalWorker.id,
                displayName = form.displayName.trim(),
                allowedScopes = scopes
            ).fold(
                ifLeft = { error ->
                    updateWorkerForm { it.withError("Error updating worker: ${error.message}") }
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to update worker"
                    )
                },
                ifRight = {
                    cancelDialog()
                }
            )
        }
    }
}
