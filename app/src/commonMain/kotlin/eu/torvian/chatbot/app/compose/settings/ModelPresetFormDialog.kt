package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.app.compose.common.ConfigTextField
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.domain.contracts.ModelPresetFormState
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Form dialog for creating or editing a model preset.
 *
 * The dialog binds the preset's name/display name/description plus its single model reference and
 * settings-profile reference. A preset bundles exactly one model with one settings profile; the
 * profile may be omitted (the server accepts a model-only preset), but a profile can never be chosen
 * before a model because a settings profile belongs to one model. The settings picker is therefore
 * **model-gated**:
 * - it is disabled while the draft has no model, with a hint explaining why;
 * - its options are limited to the selected model's profiles (plus "No settings profile");
 * - selecting another model clears a now-foreign selection, and clearing the model back to
 *   "No model" also clears it, so the UI can never *create* a settings-only preset.
 *
 * That gate is a UI affordance only, not a draft rule: a preset that already carries a settings
 * reference without a model (reachable through the REST API or the server's preset tools) shows the
 * persisted profile in the disabled picker and saves it back unchanged, so editing an unrelated field
 * never silently drops the reference.
 *
 * Provider routing (for example OpenRouter's `{"provider":{"only":[…]}}`) is not editable here: a
 * preset carries no payload of its own (U-8). It is configured on the referenced settings profile in
 * Settings → Model Settings, so the form deliberately does not duplicate that profile's content.
 *
 * @param title Dialog title ("Add Model Preset" / "Edit Model Preset").
 * @param formState The current form draft.
 * @param models All accessible models offered by the model picker (no type filter: the preset layer
 *            imposes none).
 * @param settings All accessible settings profiles; the picker filters them per selected model.
 * @param onFormUpdate Applies an update function to the form draft.
 * @param onSave Saves the form.
 * @param onCancel Cancels the dialog.
 */
@Composable
fun ModelPresetFormDialog(
    title: String,
    formState: ModelPresetFormState,
    models: List<LLMModel>,
    settings: List<ModelSettings>,
    onFormUpdate: ((ModelPresetFormState) -> ModelPresetFormState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Card(modifier = Modifier.widthIn(min = 560.dp, max = 760.dp)) {
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

                    // Set when a model change drops the previously selected settings profile as a
                    // side effect, so the form can explain the disappearance instead of silently
                    // blanking the picker (the clear is intentional, not a glitch).
                    var settingsClearedByModelChange by remember { mutableStateOf(false) }

                    val selectedModel = formState.modelId?.let { id -> models.find { it.id == id } }
                    val selectedSettings = formState.modelSettingsId?.let { id -> settings.find { it.id == id } }
                    // Options of the model-gated picker: only the selected model's profiles can be
                    // attached, plus the "No settings profile" entry (a model-only preset is legal).
                    val settingsForSelectedModel = formState.modelId
                        ?.let { modelId -> settings.filter { it.modelId == modelId } }
                        .orEmpty()
                    val modelSelected = formState.modelId != null

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ConfigTextField(
                            value = formState.name,
                            onValueChange = { value -> onFormUpdate { it.copy(name = value) } },
                            label = "Preset Name *",
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

                        // Model picker. The nullable item type expresses "No model" without touching
                        // the shared dropdown; inactive models stay selectable (the server accepts
                        // them) but are labelled so the state is visible.
                        ConfigDropdown(
                            selectedItem = selectedModel,
                            onItemSelected = { model ->
                                onFormUpdate { current ->
                                    val updated = current.withSelectedModel(model?.id, settings)
                                    settingsClearedByModelChange = current.modelSettingsId != null &&
                                            updated.modelSettingsId == null
                                    updated
                                }
                            },
                            items = listOf<LLMModel?>(null) + models,
                            label = "Model",
                            itemText = { model ->
                                model?.let { m ->
                                    val label = m.displayName?.takeIf { it.isNotBlank() } ?: m.name
                                    if (m.active) label else "$label (inactive)"
                                } ?: "No model"
                            }
                        )

                        // Model-gated settings picker: a settings profile belongs to exactly one
                        // model, so it can only be attached once a model is selected.
                        ConfigDropdown(
                            selectedItem = selectedSettings,
                            onItemSelected = { picked ->
                                if (picked != null) {
                                    onFormUpdate { it.copy(modelSettingsId = picked.id) }
                                } else {
                                    onFormUpdate { it.copy(modelSettingsId = null) }
                                }
                            },
                            items = listOf<ModelSettings?>(null) + settingsForSelectedModel,
                            label = "Settings Profile",
                            enabled = modelSelected,
                            itemText = { profile ->
                                profile?.let { "${it.name} (${it.modelType})" } ?: "No settings profile"
                            }
                        )

                        if (!modelSelected) {
                            Text(
                                text = "Select a model first — a settings profile belongs to one model.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (settingsClearedByModelChange) {
                            Text(
                                text = "Choosing 'No model' clears the settings profile.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // The persisted reference exists but cannot be resolved (deleted, or not
                        // accessible to this user); the draft keeps it and saving round-trips it.
                        if (formState.modelSettingsId != null && selectedSettings == null) {
                            Text(
                                text = "Settings profile #${formState.modelSettingsId} is not available to this client; " +
                                        "saving keeps the reference unchanged.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
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
                            Text(if (formState.mode == FormMode.NEW) "Add Preset" else "Save Changes")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Applies a newly selected model to the draft while keeping the settings selection consistent.
 *
 * A settings profile belongs to exactly one model, so:
 * - re-selecting the *current* model is a no-op (this also preserves a persisted settings reference
 *   on a model-less preset when the user re-picks "No model" in the dropdown);
 * - otherwise the current selection is kept only while it still belongs to the newly selected model,
 *   and dropped when it does not (including when the model is cleared).
 *
 * @receiver The draft being edited.
 * @param selectedModelId The newly selected model id, or null for "No model".
 * @param settings All accessible settings profiles, used to resolve the current selection's model.
 * @return The updated draft.
 */
private fun ModelPresetFormState.withSelectedModel(
    selectedModelId: Long?,
    settings: List<ModelSettings>
): ModelPresetFormState {
    if (selectedModelId == modelId) return this
    val currentSettings = modelSettingsId?.let { id -> settings.find { it.id == id } }
    val keptSettingsId = currentSettings?.takeIf { it.modelId == selectedModelId }?.id
    return copy(modelId = selectedModelId, modelSettingsId = keptSettingsId)
}
