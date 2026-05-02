package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.WorkersDialogState
import eu.torvian.chatbot.common.models.worker.WorkerDto
import kotlin.time.Instant

/**
 * Workers management tab with list and edit/delete dialogs.
 *
 * Workers are managed (edit/delete), not created - creation is handled by the worker itself.
 */
@Composable
fun WorkersTab(
    state: WorkersTabState,
    actions: WorkersTabActions,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val uiState = state.workersUiState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading workers...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load workers",
                    error = uiState.error,
                    onRetry = { actions.onLoadWorkers() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val workers = uiState.data

                if (workers.isEmpty()) {
                    EmptyWorkersList(
                        onRefresh = { actions.onLoadWorkers() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    WorkersListPage(
                        workers = workers,
                        onEditWorker = { actions.onStartEditingWorker(it) },
                        onDeleteWorker = { actions.onStartDeletingWorker(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is DataState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Click to load workers",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { actions.onLoadWorkers() }) {
                            Text("Load Workers")
                        }
                    }
                }
            }
        }

        // Dialog handling based on dialog state
        when (val dialogState = state.dialogState) {
            is WorkersDialogState.None -> {
                // No dialog open
            }

            is WorkersDialogState.EditWorker -> {
                EditWorkerDialog(
                    worker = dialogState.worker,
                    formState = dialogState.formState,
                    onDisplayNameChange = { name ->
                        actions.onUpdateWorkerForm { it.copy(displayName = name) }
                    },
                    onAllowedScopesChange = { scopes ->
                        actions.onUpdateWorkerForm { it.copy(allowedScopes = scopes) }
                    },
                    onConfirm = { actions.onSaveWorker() },
                    onDismiss = { actions.onCancelDialog() }
                )
            }

            is WorkersDialogState.DeleteWorker -> {
                DeleteWorkerConfirmationDialog(
                    worker = dialogState.worker,
                    onConfirm = {
                        actions.onDeleteWorker(dialogState.worker.id)
                    },
                    onDismiss = { actions.onCancelDialog() }
                )
            }
        }
    }
}

/**
 * Empty state when no workers are registered.
 */
@Composable
private fun EmptyWorkersList(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No workers registered",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Workers register themselves. This list shows your registered workers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRefresh) {
                Text("Refresh")
            }
        }
    }
}

/**
 * List page showing all registered workers.
 */
@Composable
private fun WorkersListPage(
    workers: List<WorkerDto>,
    onEditWorker: (WorkerDto) -> Unit,
    onDeleteWorker: (WorkerDto) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Registered Workers",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "${workers.size} worker(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(workers, key = { it.id }) { worker ->
                WorkerCard(
                    worker = worker,
                    onEdit = { onEditWorker(worker) },
                    onDelete = { onDeleteWorker(worker) }
                )
            }
        }
    }
}

/**
 * Card displaying a single worker with actions.
 */
@Composable
private fun WorkerCard(
    worker: WorkerDto,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Icon indicating if worker has been seen
                val icon = if (worker.lastSeenAt != null) {
                    Icons.Default.CloudQueue
                } else {
                    Icons.Default.CloudOff
                }
                val iconDescription = if (worker.lastSeenAt != null) {
                    "Worker has been seen"
                } else {
                    "Worker never seen"
                }

                Icon(
                    imageVector = icon,
                    contentDescription = iconDescription,
                    tint = if (worker.lastSeenAt != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = worker.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "UID: ${worker.workerUid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatLastSeen(worker.lastSeenAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit worker"
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete worker",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Formats the last seen timestamp for display.
 */
private fun formatLastSeen(lastSeenAt: Instant?): String = when (lastSeenAt) {
    null -> "Never seen"
    else -> "Last seen: ${lastSeenAt}"
}

/**
 * Dialog for editing a worker's display name and allowed scopes.
 */
@Composable
private fun EditWorkerDialog(
    worker: WorkerDto,
    formState: eu.torvian.chatbot.app.domain.contracts.WorkersFormState,
    onDisplayNameChange: (String) -> Unit,
    onAllowedScopesChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Edit Worker")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = formState.displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("Display Name") },
                    singleLine = true,
                    isError = formState.error != null && formState.displayName.isBlank(),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = formState.allowedScopes,
                    onValueChange = onAllowedScopesChange,
                    label = { Text("Allowed Scopes") },
                    placeholder = { Text("comma-separated, e.g., chat:read, chat:write") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                if (formState.error != null) {
                    Text(
                        text = formState.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text(
                    text = "Worker UID: ${worker.workerUid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Confirmation dialog for deleting a worker.
 */
@Composable
private fun DeleteWorkerConfirmationDialog(
    worker: WorkerDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Delete Worker")
        },
        text = {
            Column {
                Text("Are you sure you want to delete this worker?")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"${worker.displayName}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This action cannot be undone. The worker will need to re-register to connect again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
