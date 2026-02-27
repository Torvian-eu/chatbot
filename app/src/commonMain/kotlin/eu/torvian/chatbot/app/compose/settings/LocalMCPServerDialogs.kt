package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.DialogTestResult
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerDialogState
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerFormState
import eu.torvian.chatbot.app.viewmodel.LocalMCPToolFormState
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition

/**
 * Handles all dialog states for MCP server management.
 * Implements US6.5 - Local MCP Server Configuration Dialog.
 */
@Composable
fun LocalMCPServerDialogs(
    dialogState: LocalMCPServerDialogState,
    onUpdateForm: (update: (LocalMCPServerFormState) -> LocalMCPServerFormState) -> Unit,
    onSaveServer: () -> Unit,
    onTestServer: () -> Unit,
    onDeleteServer: (Long) -> Unit,
    onUpdateToolForm: (update: (LocalMCPToolFormState) -> LocalMCPToolFormState) -> Unit,
    onSaveTool: () -> Unit,
    onDismiss: () -> Unit,
    isServerRunning: Boolean = false
) {
    when (dialogState) {
        is LocalMCPServerDialogState.None -> {
            // No dialog
        }

        is LocalMCPServerDialogState.AddNewServer -> {
            LocalMCPServerConfigDialog(
                title = "Add New MCP Server",
                formState = dialogState.formState,
                isSaving = dialogState.isSaving,
                isTesting = dialogState.isTesting,
                testResult = dialogState.testResult,
                onUpdateForm = onUpdateForm,
                onConfirm = onSaveServer,
                onTestServer = onTestServer,
                onDismiss = onDismiss
            )
        }

        is LocalMCPServerDialogState.EditServer -> {
            LocalMCPServerConfigDialog(
                title = "Edit MCP Server",
                formState = dialogState.formState,
                isSaving = dialogState.isSaving,
                isTesting = dialogState.isTesting,
                testResult = dialogState.testResult,
                isServerRunning = isServerRunning,
                onUpdateForm = onUpdateForm,
                onConfirm = onSaveServer,
                onTestServer = onTestServer,
                onDismiss = onDismiss
            )
        }

        is LocalMCPServerDialogState.DeleteServer -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text("Delete MCP Server?")
                },
                text = {
                    Column {
                        Text("Are you sure you want to delete '${dialogState.server.name}'?")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "This will also remove all associated tools from the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onDeleteServer(dialogState.server.id) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
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

        is LocalMCPServerDialogState.EditTool -> {
            LocalMCPToolEditDialog(
                tool = dialogState.tool,
                formState = dialogState.formState,
                isSaving = dialogState.isSaving,
                onUpdateForm = onUpdateToolForm,
                onConfirm = onSaveTool,
                onDismiss = onDismiss
            )
        }
    }
}

/**
 * Configuration dialog for adding/editing MCP servers.
 * Implements US6.5 - Local MCP Server Configuration Dialog.
 */
@Composable
fun LocalMCPServerConfigDialog(
    title: String,
    formState: LocalMCPServerFormState,
    isSaving: Boolean,
    isTesting: Boolean = false,
    testResult: DialogTestResult? = null,
    isServerRunning: Boolean = false,
    onUpdateForm: (update: (LocalMCPServerFormState) -> LocalMCPServerFormState) -> Unit,
    onConfirm: () -> Unit,
    onTestServer: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // "Changes take effect after restart" banner
                if (isServerRunning) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "This server is currently running. Configuration changes will take effect after restarting the server.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                // Name (required)
                OutlinedTextField(
                    value = formState.name,
                    onValueChange = { value ->
                        onUpdateForm { it.copy(name = value, nameError = null) }
                    },
                    label = { Text("Name *") },
                    placeholder = { Text("My MCP Server") },
                    singleLine = true,
                    enabled = !isSaving,
                    isError = formState.nameError != null,
                    supportingText = formState.nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                // Description (optional)
                OutlinedTextField(
                    value = formState.description,
                    onValueChange = { value ->
                        onUpdateForm { it.copy(description = value) }
                    },
                    label = { Text("Description") },
                    placeholder = { Text("Optional description") },
                    maxLines = 3,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )

                // Command (required)
                OutlinedTextField(
                    value = formState.command,
                    onValueChange = { value ->
                        onUpdateForm { it.copy(command = value, commandError = null) }
                    },
                    label = { Text("Command *") },
                    placeholder = { Text("npx, uv, java, docker, etc.") },
                    singleLine = true,
                    enabled = !isSaving,
                    isError = formState.commandError != null,
                    supportingText = {
                        Text(formState.commandError ?: "Executable command to launch the server")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Arguments (list)
                ArgumentsSection(
                    arguments = formState.arguments,
                    enabled = !isSaving,
                    onArgumentsChange = { args ->
                        onUpdateForm { it.copy(arguments = args) }
                    }
                )

                // Environment Variables (key-value pairs)
                EnvironmentVariablesSection(
                    envVars = formState.environmentVariables,
                    enabled = !isSaving,
                    onEnvVarsChange = { envVars ->
                        onUpdateForm { it.copy(environmentVariables = envVars) }
                    }
                )

                // Working Directory (optional)
                OutlinedTextField(
                    value = formState.workingDirectory,
                    onValueChange = { value ->
                        onUpdateForm { it.copy(workingDirectory = value) }
                    },
                    label = { Text("Working Directory") },
                    placeholder = { Text("/path/to/working/dir") },
                    singleLine = true,
                    enabled = !isSaving,
                    supportingText = { Text("Optional directory to run the server in") },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // Auto-start Options
                Text(
                    text = "Auto-start Options",
                    style = MaterialTheme.typography.titleSmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-start when tool enabled")
                    Switch(
                        checked = formState.autoStartOnEnable,
                        onCheckedChange = { value ->
                            onUpdateForm { it.copy(autoStartOnEnable = value) }
                        },
                        enabled = !isSaving
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-start on application launch")
                    Switch(
                        checked = formState.autoStartOnLaunch,
                        onCheckedChange = { value ->
                            onUpdateForm { it.copy(autoStartOnLaunch = value) }
                        },
                        enabled = !isSaving
                    )
                }

                // Auto-stop timeout
                OutlinedTextField(
                    value = formState.autoStopAfterInactivitySeconds?.toString() ?: "",
                    onValueChange = { value ->
                        val intValue = value.toIntOrNull()
                        onUpdateForm { it.copy(autoStopAfterInactivitySeconds = intValue) }
                    },
                    label = { Text("Auto-stop Timeout (seconds)") },
                    placeholder = { Text("300 (default), 0 (never), or custom") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isSaving,
                    supportingText = { Text("Leave empty for default (300s), 0 to never stop") },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // Default Settings
                Text(
                    text = "Default Settings",
                    style = MaterialTheme.typography.titleSmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Server enabled")
                    Switch(
                        checked = formState.isEnabled,
                        onCheckedChange = { value ->
                            onUpdateForm { it.copy(isEnabled = value) }
                        },
                        enabled = !isSaving
                    )
                }

                // Test result (shown after a test attempt)
                testResult?.let { result ->
                    when (result) {
                        is DialogTestResult.Success -> Text(
                            text = "✓ Connected — ${result.toolCount} tool(s) discovered",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        is DialogTestResult.Failure -> Text(
                            text = "✗ ${result.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Test Server button
                OutlinedButton(
                    onClick = onTestServer,
                    enabled = !isSaving && !isTesting && formState.command.isNotBlank()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing...")
                    } else {
                        Text("Test Server")
                    }
                }

                // Save button
                Button(
                    onClick = onConfirm,
                    enabled = !isSaving && !isTesting && formState.isValid()
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isSaving) "Saving..." else "Save")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving && !isTesting
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Section for managing command-line arguments as a list.
 */
@Composable
fun ArgumentsSection(
    arguments: List<String>,
    enabled: Boolean,
    onArgumentsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Arguments",
                style = MaterialTheme.typography.titleSmall
            )
            IconButton(
                onClick = { onArgumentsChange(arguments + "") },
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Argument"
                )
            }
        }

        arguments.forEachIndexed { index, arg ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = arg,
                    onValueChange = { value ->
                        val newArgs = arguments.toMutableList()
                        newArgs[index] = value
                        onArgumentsChange(newArgs)
                    },
                    placeholder = { Text("Argument ${index + 1}") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val newArgs = arguments.toMutableList()
                        newArgs.removeAt(index)
                        onArgumentsChange(newArgs)
                    },
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove Argument",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (arguments.isEmpty()) {
            Text(
                text = "No arguments. Click + to add.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

/**
 * Section for managing environment variables as key-value pairs.
 */
@Composable
fun EnvironmentVariablesSection(
    envVars: Map<String, String>,
    enabled: Boolean,
    onEnvVarsChange: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Environment Variables",
                style = MaterialTheme.typography.titleSmall
            )
            IconButton(
                onClick = { onEnvVarsChange(envVars + ("" to "")) },
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Environment Variable"
                )
            }
        }

        Text(
            text = "Environment variables are encrypted before storage",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        envVars.entries.toList().forEachIndexed { index, (key, value) ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { newKey ->
                                val newMap = envVars.toMutableMap()
                                newMap.remove(key)
                                newMap[newKey] = value
                                onEnvVarsChange(newMap)
                            },
                            label = { Text("Key") },
                            placeholder = { Text("GITHUB_TOKEN") },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = value,
                            onValueChange = { newValue ->
                                val newMap = envVars.toMutableMap()
                                newMap[key] = newValue
                                onEnvVarsChange(newMap)
                            },
                            label = { Text("Value") },
                            placeholder = { Text("ghp_...") },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    IconButton(
                        onClick = {
                            val newMap = envVars.toMutableMap()
                            newMap.remove(key)
                            onEnvVarsChange(newMap)
                        },
                        enabled = enabled,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove Environment Variable",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (index < envVars.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        if (envVars.isEmpty()) {
            Text(
                text = "No environment variables. Click + to add.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

/**
 * Dialog for editing an MCP tool's properties.
 */
@Composable
private fun LocalMCPToolEditDialog(
    tool: LocalMCPToolDefinition,
    formState: LocalMCPToolFormState,
    isSaving: Boolean,
    onUpdateForm: (update: (LocalMCPToolFormState) -> LocalMCPToolFormState) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Edit Tool: ${tool.name}")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Tool ID (read-only)
                OutlinedTextField(
                    value = tool.id.toString(),
                    onValueChange = {},
                    label = { Text("ID") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // Tool Name (editable)
                OutlinedTextField(
                    value = formState.name,
                    onValueChange = { newValue ->
                        onUpdateForm { it.copy(name = newValue) }
                    },
                    label = { Text("Tool Name *") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                    isError = !formState.isValid(),
                    supportingText = {
                        Text("The name for this tool in your chatbot")
                    }
                )

                // Tool Description (read-only)
                OutlinedTextField(
                    value = tool.description,
                    onValueChange = {},
                    label = { Text("Description") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // Input Schema (read-only)
                OutlinedTextField(
                    value = tool.inputSchema.toString(),
                    onValueChange = {},
                    label = { Text("Input Schema") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // Output Schema (read-only)
                if (tool.outputSchema != null) {
                    OutlinedTextField(
                        value = tool.outputSchema.toString(),
                        onValueChange = {},
                        label = { Text("Output Schema") },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                // Server ID (read-only)
                OutlinedTextField(
                    value = tool.serverId.toString(),
                    onValueChange = {},
                    label = { Text("Server ID") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // Created At (read-only)
                OutlinedTextField(
                    value = tool.createdAt.toString(),
                    onValueChange = {},
                    label = { Text("Created At") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // Updated At (read-only)
                OutlinedTextField(
                    value = tool.updatedAt.toString(),
                    onValueChange = {},
                    label = { Text("Updated At") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                HorizontalDivider()

                // MCP Tool Name (read-only - original name from MCP server)
                OutlinedTextField(
                    value = tool.mcpToolName,
                    onValueChange = {},
                    label = { Text("Original MCP Tool Name") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    supportingText = {
                        Text("The original tool name from the MCP server")
                    }
                )

                // Enabled toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enabled",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Whether this tool is globally enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = formState.isEnabled,
                        onCheckedChange = { newValue ->
                            onUpdateForm { it.copy(isEnabled = newValue) }
                        },
                        enabled = !isSaving
                    )
                }

                HorizontalDivider()

                // Approval Preference section
                if (formState.approvalPreferenceActive) {
                    // Active state - show full configuration
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Header with remove button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Approval Preference",
                                style = MaterialTheme.typography.titleMedium
                            )
                            IconButton(
                                onClick = {
                                    onUpdateForm { it.copy(approvalPreferenceActive = false) }
                                },
                                enabled = !isSaving
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove approval preference"
                                )
                            }
                        }

                        // Auto-approve toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Approve",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Automatically approve calls to this tool",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = formState.autoApprove,
                                onCheckedChange = { newValue ->
                                    onUpdateForm { it.copy(autoApprove = newValue) }
                                },
                                enabled = !isSaving
                            )
                        }

                        // Conditions (optional)
                        OutlinedTextField(
                            value = formState.conditions ?: "",
                            onValueChange = { newValue ->
                                onUpdateForm { it.copy(conditions = newValue.ifBlank { null }) }
                            },
                            label = { Text("Conditions (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                            minLines = 2,
                            supportingText = {
                                Text("Specify conditions for approval (e.g., 'only when file size < 1MB')")
                            }
                        )

                        // Denial Reason (optional)
                        OutlinedTextField(
                            value = formState.denialReason ?: "",
                            onValueChange = { newValue ->
                                onUpdateForm { it.copy(denialReason = newValue.ifBlank { null }) }
                            },
                            label = { Text("Denial Reason (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                            minLines = 2,
                            supportingText = {
                                Text("Reason to show when denying this tool call")
                            }
                        )
                    }
                } else {
                    // Inactive state - show button to activate
                    OutlinedButton(
                        onClick = {
                            onUpdateForm { it.copy(approvalPreferenceActive = true) }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Approval Preference")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isSaving && formState.isValid()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isSaving) "Saving..." else "Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text("Cancel")
            }
        }
    )
}
