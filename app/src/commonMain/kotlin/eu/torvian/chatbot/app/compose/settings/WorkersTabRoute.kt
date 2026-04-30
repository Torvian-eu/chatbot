package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.WorkersViewModel
import eu.torvian.chatbot.common.models.worker.WorkerDto
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Workers settings category.
 *
 * The route keeps the ViewModel wiring and breadcrumb updates together.
 * Workers are managed (edit/delete), not created - creation is handled by the worker itself.
 *
 * @param authState Authentication context.
 * @param viewModel Workers ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category
 *   in the sidebar; triggers a reset to the list view.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the
 *   current Workers page in the breadcrumb trail.
 */
@Composable
fun WorkersTabRoute(
    authState: AuthState.Authenticated,
    viewModel: WorkersViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Tab-local initial load
    LaunchedEffect(Unit) {
        viewModel.loadWorkers()
    }

    // Collect tab state
    val workersState by viewModel.workersState.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()

    // Update breadcrumbs
    LaunchedEffect(Unit) {
        onBreadcrumbsChanged(listOf("Settings", SettingsCategory.Workers.displayLabel))
    }

    // Build presentational state
    val state = WorkersTabState(
        workersUiState = workersState,
        dialogState = dialogState
    )

    // Build actions forwarding to VM
    val actions = object : WorkersTabActions {
        override fun onLoadWorkers() = viewModel.loadWorkers()
        override fun onStartEditingWorker(worker: WorkerDto) = viewModel.startEditingWorker(worker)
        override fun onStartDeletingWorker(worker: WorkerDto) = viewModel.startDeletingWorker(worker)
        override fun onUpdateWorkerForm(update: (eu.torvian.chatbot.app.domain.contracts.WorkersFormState) -> eu.torvian.chatbot.app.domain.contracts.WorkersFormState) = viewModel.updateWorkerForm(update)
        override fun onSaveWorker() = viewModel.saveWorker()
        override fun onDeleteWorker(workerId: Long) = viewModel.deleteWorker(workerId)
        override fun onCancelDialog() = viewModel.cancelDialog()
    }

    // Call the presentational WorkersTab
    WorkersTab(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
