package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.worker.WorkerDto

/**
 * Built-in Tools management tab.
 *
 * Lets an administrator pick a registered worker and toggle the built-in tools that worker
 * exposes. The worker selection is rendered as an [ExposedDropdownMenuBox]; the tool list is a
 * scrollable [LazyColumn] of [BuiltInToolRow] cards. Tools flagged as dangerous
 * (`run_command`, `edit_file`) are visually emphasised.
 *
 * @param state Reactive UI state for the tab.
 * @param actions Callbacks invoked by user interactions.
 * @param modifier Modifier applied to the tab container.
 */
@Composable
fun BuiltInToolsTab(
    state: BuiltInToolsTabState,
    actions: BuiltInToolsTabActions,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        WorkerSelectionHeader(
            workersState = state.workersState,
            selectedWorkerId = state.selectedWorkerId,
            onWorkerSelected = actions::onSelectWorker,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        when (val toolsState = state.toolsState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading built-in tools...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load built-in tools",
                    error = toolsState.error,
                    onRetry = {
                        state.selectedWorkerId?.let { actions.onLoadTools(it) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val tools = toolsState.data
                if (tools.isEmpty()) {
                    EmptyBuiltInToolsList(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tools, key = { it.id }) { tool ->
                            BuiltInToolRow(
                                tool = tool,
                                onToggleEnabled = { actions.onToggleToolEnabled(tool) }
                            )
                        }
                    }
                }
            }

            is DataState.Idle -> {
                if (state.selectedWorkerId == null) {
                    SelectWorkerPrompt(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * Header containing the worker selection dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkerSelectionHeader(
    workersState: DataState<*, List<WorkerDto>>,
    selectedWorkerId: Long?,
    onWorkerSelected: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val workers = (workersState as? DataState.Success)?.data ?: emptyList()
    var expanded by remember { mutableStateOf(false) }

    val selectedWorker = workers.find { it.id == selectedWorkerId }

    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedWorker?.displayName ?: "",
                onValueChange = { },
                readOnly = true,
                label = { Text("Worker") },
                placeholder = { Text("Select a worker") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (workers.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No workers available") },
                        onClick = { expanded = false },
                        enabled = false
                    )
                } else {
                    workers.forEach { worker ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    worker.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                onWorkerSelected(worker.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        if (workers.isEmpty()) {
            Text(
                text = "No workers are registered. Workers register themselves.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Single built-in tool row with a trailing enable/disable switch.
 *
 * Tools considered dangerous (`run_command`, `edit_file`) are emphasised with an error-tinted
 * accent so administrators can spot them at a glance.
 *
 * @param tool The tool definition to render.
 * @param onToggleEnabled Callback invoked when the switch is flipped.
 */
@Composable
private fun BuiltInToolRow(
    tool: BuiltInWorkerToolDefinition,
    onToggleEnabled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDangerous = tool.builtInToolName in DANGEROUS_BUILT_IN_TOOLS

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDangerous) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = tool.builtInToolName,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isDangerous) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.padding(vertical = 2.dp))
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (isDangerous) {
                    Spacer(modifier = Modifier.padding(vertical = 2.dp))
                    Text(
                        text = "Dangerous operation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Switch(
                checked = tool.isEnabled,
                onCheckedChange = { onToggleEnabled() }
            )
        }
    }
}

/**
 * Placeholder shown before a worker is selected.
 */
@Composable
private fun SelectWorkerPrompt(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Select a worker to view its built-in tools.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Placeholder shown when the selected worker has no built-in tools.
 */
@Composable
private fun EmptyBuiltInToolsList(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "This worker has no built-in tools.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Built-in tool names that perform privileged filesystem or process operations and therefore
 * warrant a visual danger indication in the UI.
 */
private val DANGEROUS_BUILT_IN_TOOLS = setOf("run_command", "edit_file")
