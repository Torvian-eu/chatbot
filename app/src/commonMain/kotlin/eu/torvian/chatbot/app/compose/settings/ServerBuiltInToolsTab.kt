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
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Pretty-printing JSON formatter used to make server built-in input schemas easier to inspect and
 * edit.
 */
private val PrettyJson = Json { prettyPrint = true }

/**
 * Server Built-In Tools management tab.
 *
 * Lists the current user's per-user server built-in tool instances (e.g. `list_agent_roles`) and
 * lets the user edit each tool's description and input schema, toggle its enabled state, and
 * configure its auto-approval mode. The tab mirrors the Operator Tools tab: server built-in tools
 * are scoped to the current user and executed in-process on the server, so the list is always the
 * user's own instances. The header exposes a tool-name-prefix configuration dialog and a
 * reset-to-defaults action that reconciles the user's tools with the
 * [eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog].
 *
 * @param state Reactive UI state for the tab.
 * @param actions Callbacks invoked by user interactions.
 * @param modifier Modifier applied to the tab container.
 */
@Composable
fun ServerBuiltInToolsTab(
    state: ServerBuiltInToolsTabState,
    actions: ServerBuiltInToolsTabActions,
    modifier: Modifier = Modifier
) {
    // Tracks the tool currently being edited in the dialog (null when closed).
    var editingTool by remember { mutableStateOf<ServerBuiltInToolDefinition?>(null) }
    // Tracks whether the reset-to-defaults confirmation dialog is open.
    var showResetConfirm by remember { mutableStateOf(false) }
    // Tracks whether the tool-name-prefix configuration dialog is open.
    var showPrefixDialog by remember { mutableStateOf(false) }
    // Whether a reset is currently in flight, used to disable the button and show progress.
    val resetInProgress = state.resetInProgress

    Column(modifier = modifier.fillMaxSize()) {
        ServerBuiltInToolsHeader(
            resetEnabled = !resetInProgress,
            onReset = { showResetConfirm = true },
            onConfigurePrefix = { showPrefixDialog = true },
            resetInProgress = resetInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        when (val toolsState = state.toolsState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading server built-in tools...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load server built-in tools",
                    error = toolsState.error,
                    onRetry = { actions.onLoadTools() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val tools = toolsState.data
                if (tools.isEmpty()) {
                    EmptyServerBuiltInToolsList(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(tools, key = { it.id }) { tool ->
                            val preference = (state.approvalPreferencesState as? DataState.Success)
                                ?.data?.find { it.toolDefinitionId == tool.id }
                            ServerBuiltInToolRow(
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
                // Nothing to show until the route triggers the initial load.
            }
        }
    }

    // The dialog owns only draft text; persistence remains in the ViewModel so errors use the
    // same notification path as enable/disable updates.
    editingTool?.let { tool ->
        ServerBuiltInToolEditDialog(
            tool = tool,
            onDismiss = { editingTool = null },
            onSave = { updated ->
                actions.onUpdateTool(updated)
                editingTool = null
            }
        )
    }

    // Reset confirmation dialog. The reset runs in the ViewModel (fire-and-forget), so the
    // dialog is dismissed immediately on confirm rather than waiting for completion.
    if (showResetConfirm) {
        ServerBuiltInToolsResetConfirmationDialog(
            onConfirm = {
                actions.onResetToDefaults()
                showResetConfirm = false
            },
            onDismiss = { showResetConfirm = false }
        )
    }

    // Tool-name-prefix configuration dialog. The draft text lives inside the dialog, so the field
    // always reflects the stored value whenever the dialog is (re)opened. Like the other dialogs in
    // this tab, it closes immediately on confirm; the save/reset runs in the ViewModel
    // (fire-and-forget) and failures surface through the tab's notification path.
    if (showPrefixDialog) {
        ServerBuiltInToolsPrefixDialog(
            initialPrefix = state.toolNamePrefix ?: "",
            onSave = { prefix ->
                actions.onSaveToolNamePrefix(prefix)
                showPrefixDialog = false
            },
            onResetToDefault = {
                actions.onResetToolNamePrefix()
                showPrefixDialog = false
            },
            onDismiss = { showPrefixDialog = false }
        )
    }
}

/**
 * Header containing the tool-name-prefix configuration and reset-to-defaults actions.
 *
 * The tool-name-prefix button opens the prefix configuration dialog; the reset action reconciles
 * the user's tools with the catalog and is disabled while a reset is in flight.
 *
 * @param resetEnabled Whether the reset-to-defaults button is enabled.
 * @param onReset Callback invoked when the reset-to-defaults button is pressed.
 * @param onConfigurePrefix Callback invoked when the tool-name-prefix button is pressed.
 * @param resetInProgress Whether a reset is currently in flight, shown as inline progress.
 * @param modifier Modifier applied to the header container.
 */
@Composable
private fun ServerBuiltInToolsHeader(
    resetEnabled: Boolean,
    onReset: () -> Unit,
    onConfigurePrefix: () -> Unit,
    resetInProgress: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Server Built-In Tools",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Tools executed by the server (e.g. list_agent_roles). Configured per user.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Opens the tool-name-prefix configuration dialog.
            OutlinedButton(onClick = onConfigurePrefix) {
                Text("Tool name prefix")
            }

            // Reset-to-defaults action.
            OutlinedButton(
                onClick = onReset,
                enabled = resetEnabled
            ) {
                if (resetInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Reset")
            }
        }
    }
}

/**
 * Dialog for configuring the per-user server built-in tool name prefix.
 *
 * The prefix is concatenated (without a separator) to the canonical tool name to form the public
 * name the LLM sees, e.g. `acme-` + `list_agent_roles` → `acme-list_agent_roles`. Blank means no
 * prefix (canonical names); when no preference is stored the server default `"chatbot-"` applies
 * and is shown as the placeholder hint. The input is trimmed on save so accidental surrounding
 * whitespace is not persisted. The dialog owns its draft text, so every open starts from the latest
 * stored value; it closes immediately on confirm and the persistence runs in the ViewModel, like
 * the edit and reset confirmation dialogs in this tab.
 *
 * @param initialPrefix Stored prefix to seed the input with (`null`/absent stored value maps to
 *   `""`, with the server default shown only as the placeholder hint).
 * @param onSave Callback invoked with the trimmed prefix when the user confirms.
 * @param onResetToDefault Callback invoked when the user resets to the server default.
 * @param onDismiss Callback invoked when the dialog is dismissed without saving.
 */
@Composable
private fun ServerBuiltInToolsPrefixDialog(
    initialPrefix: String,
    onSave: (String) -> Unit,
    onResetToDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    var prefixText by remember(initialPrefix) { mutableStateOf(initialPrefix) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tool name prefix") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = prefixText,
                    onValueChange = { prefixText = it },
                    label = { Text("Tool name prefix") },
                    placeholder = { Text("chatbot-") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            "Prefix prepended to tool names (e.g. chatbot-list_agent_roles). " +
                                "Blank = no prefix. When unset, the server default \"chatbot-\" " +
                                "applies."
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // Inline reset so the server default can be restored without leaving the dialog.
                OutlinedButton(
                    onClick = onResetToDefault
                ) {
                    Text("Reset to default")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(prefixText.trim()) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Single server built-in tool row with an edit action, a trailing enable/disable switch, and an
 * auto-approval dropdown.
 *
 * The public name remains the catalog identity used to dispatch the server built-in tool and is
 * read-only (the server ignores rename attempts). The description and input schema can be
 * customized per user; enabled state and approval preference are also user-configurable.
 *
 * @param tool The tool definition to render.
 * @param approvalPreference The user's current approval preference for this tool, or null.
 * @param onToggleEnabled Callback invoked when the switch is flipped.
 * @param onEdit Callback invoked when the edit action is pressed.
 * @param onSetApprovalPreference Callback invoked when auto-approval is enabled (auto-approve).
 * @param onClearApprovalPreference Callback invoked when the preference is reset to manual
 *   ("Requires User Approval") approval, removing the stored preference row.
 * @param modifier Modifier applied to the tool row card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerBuiltInToolRow(
    tool: ServerBuiltInToolDefinition,
    approvalPreference: UserToolApprovalPreference?,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onSetApprovalPreference: (Boolean) -> Unit,
    onClearApprovalPreference: () -> Unit,
    modifier: Modifier = Modifier
) {
    var approvalExpanded by remember { mutableStateOf(false) }
    // Default behaviour (no stored preference) is "Requires User Approval": the user is prompted in
    // the UI for every call. An explicit preference overrides the default: autoApprove=true ->
    // Auto-Approve, autoApprove=false -> Auto-Deny.
    val approvalMode = when {
        approvalPreference == null -> "Requires User Approval"
        approvalPreference.autoApprove -> "Auto-Approve"
        else -> "Auto-Deny"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                        text = tool.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
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
                    value = approvalMode,
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
                    DropdownMenuItem(
                        text = { Text("Auto-Deny") },
                        onClick = {
                            onSetApprovalPreference(false)
                            approvalExpanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Dialog for editing a server built-in tool's description and input schema.
 *
 * The public name is shown as read-only because it is the catalog identity and execution
 * discriminator used by the server's built-in dispatcher. Schema input is validated as a JSON
 * object before saving so the LLM cannot receive a malformed tool definition from this form.
 *
 * @param tool The server built-in tool definition being edited.
 * @param onDismiss Callback invoked when the dialog closes without saving.
 * @param onSave Callback invoked with the updated definition after validation succeeds.
 */
@Composable
private fun ServerBuiltInToolEditDialog(
    tool: ServerBuiltInToolDefinition,
    onDismiss: () -> Unit,
    onSave: (ServerBuiltInToolDefinition) -> Unit
) {
    var description by remember { mutableStateOf(tool.description) }
    // Pretty-print the existing schema once so the initial form is readable without changing its meaning.
    var schemaText by remember {
        mutableStateOf(PrettyJson.encodeToString(JsonObject.serializer(), tool.inputSchema))
    }
    val schemaParseResult = remember(schemaText) {
        runCatching {
            Json.parseToJsonElement(schemaText).let { element ->
                element as? JsonObject
                    ?: throw IllegalArgumentException("Input schema must be a JSON object")
            }
        }
    }
    val parsedSchema = schemaParseResult.getOrNull()
    val schemaError = schemaParseResult.exceptionOrNull()?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Tool: ${tool.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // The name is immutable because the server uses it to select the built-in executor.
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

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = schemaText,
                    onValueChange = { schemaText = it },
                    label = { Text("Input Schema (JSON)") },
                    singleLine = false,
                    minLines = 6,
                    isError = parsedSchema == null,
                    supportingText = {
                        Text(schemaError ?: "Valid JSON schema")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedSchema != null && description.isNotBlank(),
                onClick = {
                    parsedSchema?.let { schema ->
                        onSave(tool.copy(description = description, inputSchema = schema))
                    }
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
 * Placeholder shown when the current user has no server built-in tools.
 */
@Composable
private fun EmptyServerBuiltInToolsList(modifier: Modifier = Modifier) {
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
                text = "No server built-in tools are configured for this user.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Confirmation dialog shown before resetting the user's server built-in tools to the catalog
 * defaults.
 *
 * Resetting overwrites each tool's description and input schema with the catalog values and adds
 * any tools that are missing, so the user must explicitly confirm. Enabled/disabled choices and
 * approval preferences are preserved, which is surfaced in the dialog copy.
 *
 * @param onConfirm Callback invoked when the user confirms the reset.
 * @param onDismiss Callback invoked when the dialog is dismissed without confirming.
 */
@Composable
private fun ServerBuiltInToolsResetConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset server built-in tools?") },
        text = {
            Text(
                "This restores your server built-in tools to their default definitions. Custom " +
                    "descriptions and input schemas will be overwritten, and any missing tools " +
                    "will be added. Enabled/disabled choices and approval preferences are kept."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Reset")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
