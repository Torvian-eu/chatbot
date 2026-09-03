package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference

/**
 * Conversation Compaction settings tab: compaction model selector, compatible non-streaming settings
 * selector, instruction editor, optional system-message editor, summary-label editor, token-threshold
 * editor (default 100,000), inline validation messages, an enable/disable toggle, and Save / Delete
 * actions backed by the GLOBAL `conversation_compaction` preference row.
 *
 * An absent stored preference means automatic compaction is disabled and the form shows the disabled
 * defaults. The enable toggle is draft state persisted by Save: turning it off and saving keeps the
 * stored configuration while disabling compaction at runtime (the server stores `enabled = false`),
 * which is deliberately separate from the destructive Delete action. The form only renders
 * accessible, active models and chat-like non-streaming profiles, so the saved configuration is
 * compatible with the server's write-time validation. The form stays fully editable whether or not
 * compaction is enabled.
 *
 * @param state The current tab state.
 * @param actions The action callbacks for the tab.
 * @param modifier Modifier applied to the tab.
 */
@Composable
fun ConversationCompactionTab(
    state: ConversationCompactionTabState,
    actions: ConversationCompactionTabActions,
    modifier: Modifier = Modifier
) {
    var modelExpanded by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Conversation Compaction",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Enable-status banner with the draft toggle. Disabling is decoupled from deletion: toggling
        // off and saving writes `enabled = false` into the stored row (runtime-disabled, configuration
        // preserved), while the destructive Delete action at the bottom removes the row entirely.
        item {
            val stored = state.storedPreference
            val effectiveEnabled = stored != null && stored.enabled
            Surface(
                color = if (effectiveEnabled) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                stored != null && stored.enabled ->
                                    "Automatic compaction is enabled (model: ${storedModelLabel(stored)}). " +
                                        "Oversized primary contexts are summarized before the assistant responds."

                                stored != null ->
                                    "Automatic compaction is disabled, but the stored configuration is preserved " +
                                        "(model: ${storedModelLabel(stored)}). Toggle it on and save to re-enable."

                                else ->
                                    "Automatic compaction is disabled. No saved configuration exists; " +
                                        "configure the form below and save to enable."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = actions::onToggleEnabled
                    )
                }
            }
        }

        // Compaction model selector (accessible, active models only).
        item {
            val models = when (val modelsState = state.models) {
                is DataState.Error -> {
                    Text(
                        text = "Failed to load models: ${modelsState.error.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    emptyList()
                }

                is DataState.Loading, DataState.Idle -> {
                    // Idle (initial state before load) renders like Loading so a not-yet-loaded
                    // model list never flashes the misleading "No accessible models" empty state.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Loading models…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    emptyList()
                }

                is DataState.Success -> modelsState.data
            }
            ModelSelectionField(
                models = models,
                selectedModelId = state.selectedModel?.id,
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it },
                onSelect = actions::onSelectModel
            )
        }

        // Compatible settings-profile selector for the chosen model.
        item {
            SettingsSelectionField(
                settingsProfiles = state.compatibleSettings,
                selectedSettingsId = state.selectedSettings?.settings?.id,
                expanded = settingsExpanded,
                onExpandedChange = { settingsExpanded = it },
                onSelect = actions::onSelectSettings,
                enabled = state.selectedModel != null
            )
        }

        // Compaction instruction editor.
        item {
            OutlinedTextField(
                value = state.instruction,
                onValueChange = actions::onUpdateInstruction,
                label = { Text("Compaction instruction") },
                placeholder = { Text("e.g. Summarize the conversation faithfully; keep every decision, name, and open question.") },
                supportingText = {
                    Text("Sent to the auxiliary model with the over-threshold window; never included in compaction events.")
                },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Optional system-message editor; blank maps to null (no system prompt) on save.
        item {
            OutlinedTextField(
                value = state.systemMessage,
                onValueChange = actions::onUpdateSystemMessage,
                label = { Text("System message (optional)") },
                placeholder = { Text("e.g. You are a faithful summarization assistant; preserve every decision, name, and open question.") },
                supportingText = {
                    Text("Optional system prompt for the auxiliary compaction request; blank sends none. Never included in compaction events.")
                },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Summary-label editor; blank reverts to the stable default on save.
        item {
            OutlinedTextField(
                value = state.summaryLabel,
                onValueChange = actions::onUpdateSummaryLabel,
                label = { Text("Summary label") },
                placeholder = { Text(ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL.trimEnd()) },
                supportingText = {
                    Text("Label prefix of the synthetic summary message in the primary context. Default: \"${ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL.trimEnd()}\"")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Token-threshold editor (default 100,000).
        item {
            OutlinedTextField(
                value = state.thresholdText,
                onValueChange = actions::onUpdateThreshold,
                label = { Text("Token threshold") },
                supportingText = {
                    Text("Compaction triggers when the primary input exceeds this approximate token count. Default: 100,000.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Clear validation display: missing/invalid configuration is listed before any save attempt.
        if (state.validationErrors.isNotEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Configuration is incomplete:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Render each validation message as a bullet inside the surface; the list is
                        // short, so a plain Column is simpler than a nested lazy scope.
                        state.validationErrors.forEach { message ->
                            Text(
                                text = "• $message",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // Save / Delete actions. Save persists the draft incl. its toggle; Delete is the destructive
        // removal of the stored row and is only available while a row exists.
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = actions::onSave,
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (state.storedPreference != null) "Save changes" else "Save configuration")
                }
                OutlinedButton(
                    onClick = actions::onClear,
                    enabled = !state.saving && state.storedPreference != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Delete configuration")
                }
            }
        }

        item {
            Text(
                text = "The compaction model receives the over-threshold window; only non-streaming chat-like " +
                    "settings profiles are compatible. Changing models clears the settings selection because " +
                    "profiles belong to one model. Toggling compaction off and saving disables it temporarily " +
                    "while keeping the configuration; Delete configuration removes the stored preference entirely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Renders the compaction-model selector as a read-only dropdown.
 *
 * @param models Accessible active models to choose from.
 * @param selectedModelId Currently selected model id, or null.
 * @param expanded Whether the dropdown is expanded.
 * @param onExpandedChange Toggles dropdown expansion.
 * @param onSelect Selects a model by id (null clears).
 * @param modifier Modifier applied to the dropdown box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionField(
    models: List<eu.torvian.chatbot.common.models.llm.LLMModel>,
    selectedModelId: Long?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = models.firstOrNull { it.id == selectedModelId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected?.let { it.displayName ?: it.name } ?: "Select a compaction model",
            onValueChange = { },
            readOnly = true,
            label = { Text("Compaction model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No accessible models") },
                    enabled = false,
                    onClick = { onExpandedChange(false) }
                )
            } else {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.displayName ?: model.name) },
                        onClick = {
                            onSelect(model.id)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Renders the compatible settings-profile selector as a read-only dropdown.
 *
 * @param settingsProfiles Compatible non-streaming profiles for the chosen model.
 * @param selectedSettingsId Currently selected settings id, or null.
 * @param expanded Whether the dropdown is expanded.
 * @param onExpandedChange Toggles dropdown expansion.
 * @param onSelect Selects a settings profile by id (null clears).
 * @param enabled Whether a model has been selected (profiles are model-bound).
 * @param modifier Modifier applied to the dropdown box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSelectionField(
    settingsProfiles: List<eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails>,
    selectedSettingsId: Long?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Long?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val selected = settingsProfiles.firstOrNull { it.settings.id == selectedSettingsId }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = onExpandedChange,
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selected?.settings?.name ?: if (enabled) "Select a settings profile" else "Select a model first",
            onValueChange = { },
            readOnly = true,
            enabled = enabled,
            label = { Text("Settings profile (non-streaming)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            if (settingsProfiles.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No non-streaming chat-like profiles for this model") },
                    enabled = false,
                    onClick = { onExpandedChange(false) }
                )
            } else {
                settingsProfiles.forEach { details ->
                    DropdownMenuItem(
                        text = { Text(details.settings.name) },
                        onClick = {
                            onSelect(details.settings.id)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Builds a short human-readable label for the enabled-status banner.
 *
 * @param stored The stored preference.
 * @return The model display name when available, otherwise a fallback describing the missing row.
 */
private fun storedModelLabel(stored: ConversationCompactionPreference): String =
    stored.modelId?.toString() ?: "deleted/unknown"