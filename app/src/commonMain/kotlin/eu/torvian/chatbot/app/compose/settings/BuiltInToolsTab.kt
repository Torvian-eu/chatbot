package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import eu.torvian.chatbot.common.models.worker.WorkerDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Pretty-printing JSON formatter used to render the editable input schema in the tool edit
 * dialog. Reused across invocations to avoid the cost of constructing a new [Json] instance
 * for every dialog.
 */
private val PrettyJson = Json { prettyPrint = true }

/**
 * Built-in Tools management tab.
 *
 * Lets an administrator pick a registered worker and toggle the built-in tools that worker
 * exposes. The worker selection is rendered as an [ExposedDropdownMenuBox]; the tool list is a
 * scrollable [LazyColumn] of [BuiltInToolRow] cards. Each row exposes an edit action (to change
 * the description and input schema) and an auto-approval dropdown. Tools flagged as dangerous
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
    // Tracks the tool currently being edited in the dialog (null when closed).
    var editingTool by remember { mutableStateOf<BuiltInWorkerToolDefinition?>(null) }

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
                            val preference = (state.approvalPreferencesState as? DataState.Success)
                                ?.data?.find { it.toolDefinitionId == tool.id }
                            BuiltInToolRow(
                                tool = tool,
                                approvalPreference = preference,
                                onToggleEnabled = { actions.onToggleToolEnabled(tool) },
                                onEdit = { editingTool = tool },
                                onSetApprovalPreference = { autoApprove ->
                                    actions.onSetApprovalPreference(tool.id, autoApprove)
                                },
                                onClearApprovalPreference = {
                                    actions.onClearApprovalPreference(tool.id)
                                }
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

    // Edit dialog for description + input schema.
    editingTool?.let { tool ->
        BuiltInToolEditDialog(
            tool = tool,
            onDismiss = { editingTool = null },
            onSave = { updated ->
                actions.onUpdateTool(updated)
                editingTool = null
            }
        )
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
 * Single built-in tool row with a trailing enable/disable switch, an edit action, and an
 * auto-approval dropdown.
 *
 * Tools considered dangerous (`run_command`, `edit_file`) are emphasised with an error-tinted
 * accent so administrators can spot them at a glance.
 *
 * @param tool The tool definition to render.
 * @param approvalPreference The user's current approval preference for this tool, or null.
 * @param onToggleEnabled Callback invoked when the switch is flipped.
 * @param onEdit Callback invoked when the edit action is pressed.
 * @param onSetApprovalPreference Callback invoked when auto-approval is enabled (auto-approve).
 * @param onClearApprovalPreference Callback invoked when the preference is reset to manual
 *   ("Requires User Approval") approval, removing the stored preference row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BuiltInToolRow(
    tool: BuiltInWorkerToolDefinition,
    approvalPreference: UserToolApprovalPreference?,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onSetApprovalPreference: (Boolean) -> Unit,
    onClearApprovalPreference: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDangerous = tool.builtInToolName in DANGEROUS_BUILT_IN_TOOLS
    var approvalExpanded by remember { mutableStateOf(false) }
    // Default behaviour (no preference) is "Requires User Approval".
    val autoApprove = approvalPreference?.autoApprove ?: false

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isDangerous) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Dangerous operation",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit tool",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = tool.isEnabled,
                    onCheckedChange = { onToggleEnabled() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-approval mode selector.
            ExposedDropdownMenuBox(
                expanded = approvalExpanded,
                onExpandedChange = { approvalExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = if (autoApprove) "Auto-Approve" else "Requires User Approval",
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Approval mode") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = approvalExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )

                ExposedDropdownMenu(
                    expanded = approvalExpanded,
                    onDismissRequest = { approvalExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Requires User Approval") },
                        onClick = {
                            // "Requires User Approval" means no stored preference, so the
                            // preference row is deleted rather than set to auto-deny (false).
                            onClearApprovalPreference()
                            approvalExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Auto-Approve") },
                        onClick = {
                            onSetApprovalPreference(true)
                            approvalExpanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Dialog for editing a built-in tool's description and input schema.
 *
 * The input schema is edited as raw, formatted JSON. Saving is blocked while the schema text
 * is not valid JSON, preventing the client from sending a malformed payload to the server.
 *
 * @param tool The tool definition being edited.
 * @param onDismiss Callback invoked when the dialog is dismissed without saving.
 * @param onSave Callback invoked with the updated definition when the user confirms.
 */
@Composable
private fun BuiltInToolEditDialog(
    tool: BuiltInWorkerToolDefinition,
    onDismiss: () -> Unit,
    onSave: (BuiltInWorkerToolDefinition) -> Unit
) {
    var description by remember { mutableStateOf(tool.description) }
    // Pretty-print the schema for a friendlier editing experience.
    var schemaText by remember {
        mutableStateOf(PrettyJson.encodeToString(JsonObject.serializer(), tool.inputSchema))
    }
    var schemaError by remember { mutableStateOf<String?>(null) }

    val isSchemaValid = remember(schemaText) {
        try {
            Json.parseToJsonElement(schemaText)
            schemaError = null
            true
        } catch (e: Exception) {
            schemaError = e.message ?: "Invalid JSON"
            false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Tool: ${tool.builtInToolName}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Public name (read-only, immutable identity).
                OutlinedTextField(
                    value = tool.name,
                    onValueChange = { },
                    label = { Text("Public Name") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // Editable description.
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                // Editable input schema (raw JSON, monospace).
                OutlinedTextField(
                    value = schemaText,
                    onValueChange = { schemaText = it },
                    label = { Text("Input Schema (JSON)") },
                    singleLine = false,
                    minLines = 6,
                    isError = !isSchemaValid,
                    supportingText = {
                        if (isSchemaValid) {
                            Text("Valid JSON schema")
                        } else {
                            Text(schemaError ?: "Invalid JSON")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isSchemaValid && description.isNotBlank(),
                onClick = {
                    val schemaElement = Json.parseToJsonElement(schemaText)
                    val updatedSchema = schemaElement as JsonObject
                    onSave(
                        tool.copy(
                            description = description,
                            inputSchema = updatedSchema
                        )
                    )
                }
            ) {
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
private val DANGEROUS_BUILT_IN_TOOLS = setOf("run_command")
