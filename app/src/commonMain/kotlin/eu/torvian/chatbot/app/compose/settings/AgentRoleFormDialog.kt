package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.app.compose.common.ConfigTextField
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFormState
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.domain.contracts.defaultInstructionName
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition

/**
 * The instruction types offered in the role form's type selector.
 *
 * `MODEL_SETTINGS` is included so the user can place it anywhere in the ordered instruction list:
 * its `message` is resolved server-side from the role's settings profile, so only that field stays
 * read-only — the type, label, position and removal remain user-controlled. `ROLE`, `MAIN` and
 * `MODEL_SETTINGS` are single-instance (mirroring the server's validation); only `CUSTOM` may appear
 * more than once.
 */
private val EDITABLE_INSTRUCTION_TYPES = listOf(
    AgentInstructionTypes.ROLE,
    AgentInstructionTypes.MAIN,
    AgentInstructionTypes.CUSTOM,
    AgentInstructionTypes.MODEL_SETTINGS,
    AgentInstructionTypes.SPAWNABLE_AGENTS
)

/**
 * Form dialog for creating or editing an agent role.
 *
 * The dialog binds the role's model, settings profile, tools and ordered instruction list. Switching
 * the model clears the settings selection because profiles belong to a specific model. A
 * `MODEL_SETTINGS` instruction can be placed anywhere in the list and reordered; only its message is
 * read-only (the server resolves it from the role's settings profile).
 *
 * @param title Dialog title ("Add Agent Role" / "Edit Agent Role").
 * @param formState The current form draft.
 * @param models Chat-capable models available for selection.
 * @param settingsForModel Chat-capable settings profiles for the model currently chosen in the form.
 * @param tools Enabled tool definitions available for the multi-select.
 * @param roles Same-user roles available as spawn targets, including the edited role (self-spawn is
 *            allowed).
 * @param onFormUpdate Applies an update function to the form draft.
 * @param onSave Saves the form.
 * @param onCancel Cancels the dialog.
 */
@Composable
fun AgentRoleFormDialog(
    title: String,
    formState: AgentRoleFormState,
    models: List<LLMModel>,
    settingsForModel: List<ModelSettings>,
    tools: List<ToolDefinition>,
    roles: List<AgentRoleDto>,
    onFormUpdate: ((AgentRoleFormState) -> AgentRoleFormState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Card(modifier = Modifier.widthIn(min = 560.dp, max = 760.dp)) {
            // Hoisted scroll state so the desktop scrollbar tracks the dialog body; the max height
            // keeps very tall role drafts from exceeding the screen while the body scrolls inside.
            val scrollState = rememberScrollState()
            ScrollbarWrapper(
                scrollState = scrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(24.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ConfigTextField(
                            value = formState.name,
                            onValueChange = { value -> onFormUpdate { it.copy(name = value) } },
                            label = "Role Name *",
                            isError = formState.name.isBlank()
                        )
                        ConfigTextField(
                            value = formState.displayName,
                            onValueChange = { value -> onFormUpdate { it.copy(displayName = value) } },
                            label = "Display Name"
                        )
                        ConfigTextField(
                            value = formState.description,
                            onValueChange = { value -> onFormUpdate { it.copy(description = value) } },
                            label = "Description",
                            singleLine = false,
                            modifier = Modifier.height(80.dp)
                        )

                        // Model selector. Switching the model clears the settings selection because
                        // settings profiles belong to a specific model.
                        ConfigDropdown(
                            selectedItem = formState.modelId?.let { id -> models.find { it.id == id } },
                            onItemSelected = { model ->
                                onFormUpdate { it.copy(modelId = model.id, modelSettingsId = null) }
                            },
                            items = models,
                            label = "Model *",
                            itemText = { it.displayName ?: it.name }
                        )

                        ConfigDropdown(
                            selectedItem = formState.modelSettingsId?.let { id ->
                                settingsForModel.find { it.id == id }
                            },
                            onItemSelected = { settings ->
                                onFormUpdate { it.copy(modelSettingsId = settings.id) }
                            },
                            items = settingsForModel,
                            label = "Settings Profile *",
                            itemText = { it.name }
                        )

                        // Tools multi-select (FilterChip row).
                        Text("Tools", style = MaterialTheme.typography.titleSmall)
                        if (tools.isEmpty()) {
                            Text(
                                text = "No enabled tools available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                tools.forEach { tool ->
                                    val selected = tool.id in formState.toolIds
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            onFormUpdate { current ->
                                                current.copy(
                                                    toolIds = if (selected) {
                                                        current.toolIds - tool.id
                                                    } else {
                                                        current.toolIds + tool.id
                                                    }
                                                )
                                            }
                                        },
                                        label = { Text(tool.name, maxLines = 1) }
                                    )
                                }
                            }
                        }

                        // Spawn permissions are an unordered set: toggling a chip simply adds or
                        // removes the target, and self-spawn is allowed (the edited role is included).
                        Text("Spawnable agent roles", style = MaterialTheme.typography.titleSmall)
                        if (roles.isEmpty()) {
                            Text(
                                text = "No agent roles are available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                roles.forEach { target ->
                                    val selected = target.id in formState.spawnableAgentRoleIds
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            onFormUpdate { current ->
                                                current.copy(
                                                    spawnableAgentRoleIds = if (selected) {
                                                        current.spawnableAgentRoleIds - target.id
                                                    } else {
                                                        current.spawnableAgentRoleIds + target.id
                                                    }
                                                )
                                            }
                                        },
                                        label = {
                                            val display = target.displayName?.takeIf { it.isNotBlank() }
                                            Text(if (display == null) target.name else "${target.name} — $display")
                                        }
                                    )
                                }
                            }
                        }

                        // Instruction list editor.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Instructions", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = {
                                onFormUpdate { current ->
                                    current.copy(
                                        instructions = current.instructions + AgentInstructionDto(
                                            type = AgentInstructionTypes.CUSTOM,
                                            name = defaultInstructionName(AgentInstructionTypes.CUSTOM),
                                            message = ""
                                        )
                                    )
                                }
                            }) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add")
                            }
                        }

                        if (formState.instructions.isEmpty()) {
                            Text(
                                text = "No instructions. Add one to shape the role's system prompt.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            formState.instructions.forEachIndexed { index, instruction ->
                                val isModelSettings = instruction.type == AgentInstructionTypes.MODEL_SETTINGS
                                val isSpawnableAgents = instruction.type == AgentInstructionTypes.SPAWNABLE_AGENTS
                                // Unknown/future instruction kinds stay fully read-only as a defensive
                                // fallback until the client learns their semantics.
                                val isUnknownType = instruction.type !in EDITABLE_INSTRUCTION_TYPES
                                // Types already used by OTHER rows. ROLE/MAIN/MODEL_SETTINGS are
                                // single-instance (the server rejects duplicates); CUSTOM is multi-instance.
                                val unavailableTypes = formState.instructions
                                    .filterIndexed { i, _ -> i != index }
                                    .map { it.type }
                                    .toSet()
                                InstructionEditorRow(
                                    index = index,
                                    instruction = instruction,
                                    isMessageReadOnly = isModelSettings || isSpawnableAgents,
                                    isFullyReadOnly = isUnknownType,
                                    unavailableTypes = unavailableTypes,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < formState.instructions.lastIndex,
                                    onUpdate = { updated ->
                                        onFormUpdate { current ->
                                            current.copy(
                                                instructions = current.instructions.mapIndexed { i, inst ->
                                                    if (i == index) updated else inst
                                                }
                                            )
                                        }
                                    },
                                    onMoveUp = {
                                        onFormUpdate { current ->
                                            current.copy(instructions = current.instructions.swap(index, index - 1))
                                        }
                                    },
                                    onMoveDown = {
                                        onFormUpdate { current ->
                                            current.copy(instructions = current.instructions.swap(index, index + 1))
                                        }
                                    },
                                    onRemove = {
                                        onFormUpdate { current ->
                                            current.copy(instructions = current.instructions.filterIndexed { i, _ -> i != index })
                                        }
                                    }
                                )
                            }
                        }

                        // Validation error surfaced by the ViewModel.
                        formState.errorMessage?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onSave,
                            enabled = formState.name.isNotBlank()
                        ) {
                            Text(if (formState.mode == FormMode.NEW) "Add Role" else "Save Changes")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Editor row for a single instruction entry.
 *
 * `ROLE`/`MAIN`/`CUSTOM` instructions are fully editable. `MODEL_SETTINGS` and `SPAWNABLE_AGENTS`
 * entries keep their generated messages read-only; their type, name, position and removal remain
 * user-controlled. Unknown/future instruction kinds are rendered fully
 * read-only as a defensive fallback until the client learns their semantics.
 *
 * @param index Position in the instruction list.
 * @param instruction The instruction being edited.
 * @param isMessageReadOnly Whether the message is generated server-side and must not be edited.
 * @param isFullyReadOnly Whether the whole row is read-only (unknown instruction kinds).
 * @param unavailableTypes Types already used by other rows. The single-instance kinds
 *            (`ROLE`/`MAIN`/`MODEL_SETTINGS`) are offered only while not in this set; `CUSTOM` is
 *            always selectable because it is multi-instance.
 * @param canMoveUp Whether the up-move button is enabled.
 * @param canMoveDown Whether the down-move button is enabled.
 * @param onUpdate Callback with the updated instruction.
 * @param onMoveUp Moves the instruction one position up.
 * @param onMoveDown Moves the instruction one position down.
 * @param onRemove Removes the instruction from the list.
 */
@Composable
private fun InstructionEditorRow(
    index: Int,
    instruction: AgentInstructionDto,
    isMessageReadOnly: Boolean,
    isFullyReadOnly: Boolean,
    unavailableTypes: Set<String>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (AgentInstructionDto) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Expanded state lives here so both the message field and the footer toggle share it.
            var messageExpanded by remember { mutableStateOf(false) }

            // Header row: the type selector is full-width so it lines up with the name/message
            // fields below; the remove action sits on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isFullyReadOnly) {
                    Text(
                        text = "Type: ${instruction.type} (read-only)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    ConfigDropdown(
                        selectedItem = instruction.type,
                        onItemSelected = { type ->
                            val typeChanged = type != instruction.type
                            onUpdate(
                                instruction.copy(
                                    type = type,
                                    // Selecting a type re-labels the row with the type's conventional
                                    // default name so the row stays recognizably named.
                                    name = if (typeChanged) defaultInstructionName(type) else instruction.name,
                                    // Switching to MODEL_SETTINGS clears the draft message: the server
                                    // resolves the real text from the role's settings profile, so any
                                    // typed content would be misleading (and is ignored on read).
                                    message = if (typeChanged && type in setOf(
                                            AgentInstructionTypes.MODEL_SETTINGS,
                                            AgentInstructionTypes.SPAWNABLE_AGENTS
                                        )
                                    ) {
                                        ""
                                    } else {
                                        instruction.message
                                    }
                                )
                            )
                        },
                        items = EDITABLE_INSTRUCTION_TYPES,
                        label = "Type",
                        modifier = Modifier.weight(1f),
                        itemText = { it },
                        // CUSTOM is multi-instance; the single-instance kinds (ROLE/MAIN/MODEL_SETTINGS)
                        // are offered only while no other row already uses them, so the user cannot
                        // build a role the server would reject.
                        itemEnabled = { type ->
                            type == AgentInstructionTypes.CUSTOM || type !in unavailableTypes
                        }
                    )
                }
                IconButton(
                    onClick = onRemove,
                    enabled = !isFullyReadOnly,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Remove instruction")
                }
            }

            if (!isFullyReadOnly) {
                ConfigTextField(
                    value = instruction.name,
                    onValueChange = { value -> onUpdate(instruction.copy(name = value)) },
                    label = "Name"
                )
            }
            // The message starts at three lines; the footer toggle expands it to reveal long content
            // (or collapses it back). Kept available for read-only rows too, so long server-resolved
            // text (e.g. model settings) can be inspected.
            ConfigTextField(
                value = instruction.message,
                onValueChange = { value ->
                    if (!isMessageReadOnly && !isFullyReadOnly) {
                        onUpdate(instruction.copy(message = value))
                    }
                },
                label = "Message",
                singleLine = false,
                minLines = 3,
                maxLines = if (messageExpanded) 12 else 3,
                enabled = !isMessageReadOnly && !isFullyReadOnly,
                placeholder = if (isMessageReadOnly) {
                    if (instruction.type == AgentInstructionTypes.SPAWNABLE_AGENTS) {
                        "Generated from the selected spawnable roles"
                    } else {
                        "Auto-resolved from the role's settings profile"
                    }
                } else {
                    ""
                }
            )

            // Footer row: position number, purpose hint and all row actions (reorder + expand)
            // grouped together so the editing fields above stay clean and aligned.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = instructionHint(instruction.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                }
                IconButton(
                    onClick = { messageExpanded = !messageExpanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (messageExpanded) {
                            Icons.Default.ExpandLess
                        } else {
                            Icons.Default.ExpandMore
                        },
                        contentDescription = if (messageExpanded) "Collapse message" else "Expand message"
                    )
                }
            }
        }
    }
}

/**
 * Short user-facing hint describing what an instruction type does, shown in the row's footer.
 *
 * @param type The [AgentInstructionTypes] key.
 * @return A one- to two-line hint; unknown keys get a generic read-only note.
 */
private fun instructionHint(type: String): String = when (type) {
    AgentInstructionTypes.ROLE ->
        "Defines the assistant's role — e.g. 'You are a senior software architect'."

    AgentInstructionTypes.MAIN ->
        "Main instruction — sets the primary behavior or project context."

    AgentInstructionTypes.MODEL_SETTINGS ->
        "Auto — uses the system prompt from the role's settings profile."

    AgentInstructionTypes.CUSTOM ->
        "Free-form instruction — add anything you want the model to follow."

    AgentInstructionTypes.SPAWNABLE_AGENTS ->
        "Auto — advertises the roles selected above to the spawn_agent tool."

    else -> "Unknown instruction type — read-only."
}

/**
 * Swaps two elements in a list. Used by the instruction reorder buttons.
 */
private fun <T> List<T>.swap(first: Int, second: Int): List<T> {
    if (first !in indices || second !in indices || first == second) return this
    return toMutableList().apply {
        val tmp = this[first]
        this[first] = this[second]
        this[second] = tmp
    }
}
