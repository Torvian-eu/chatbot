package eu.torvian.chatbot.app.compose.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.domain.contracts.ModelPresetDialogState
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Dialog router for the Model Presets tab.
 *
 * Dispatches to the form or confirmation dialog based on the current [ModelPresetDialogState], and
 * feeds the form its model/settings catalogs.
 *
 * @param dialogState The current dialog state from the ViewModel.
 * @param actions ViewModel-forwarding actions.
 * @param models All accessible models available for the form's model picker.
 * @param settings All accessible settings profiles for the form's model-gated settings picker.
 */
@Composable
fun ModelPresetsDialogs(
    dialogState: ModelPresetDialogState,
    actions: ModelPresetsTabActions,
    models: List<LLMModel>,
    settings: List<ModelSettings>
) {
    when (dialogState) {
        is ModelPresetDialogState.AddPreset -> {
            ModelPresetFormDialog(
                title = "Add Model Preset",
                formState = dialogState.formState,
                models = models,
                settings = settings,
                onFormUpdate = actions::onUpdatePresetForm,
                onSave = actions::onSavePreset,
                onCancel = actions::onCancelDialog
            )
        }

        is ModelPresetDialogState.EditPreset -> {
            ModelPresetFormDialog(
                title = "Edit Model Preset",
                formState = dialogState.formState,
                models = models,
                settings = settings,
                onFormUpdate = actions::onUpdatePresetForm,
                onSave = actions::onSavePreset,
                onCancel = actions::onCancelDialog
            )
        }

        is ModelPresetDialogState.DeletePreset -> {
            // Unconditional confirmation: the dialog states the one factual consequence (bound roles
            // become non-sendable) but deliberately computes no affected-role count or list, so the
            // presets tab never has to depend on the role stream (U-34/RQ-4).
            AlertDialog(
                onDismissRequest = actions::onCancelDialog,
                title = { Text("Delete Model Preset") },
                text = {
                    val name = dialogState.preset.displayName?.takeIf { it.isNotBlank() } ?: dialogState.preset.name
                    Text(
                        "Are you sure you want to delete the preset '$name'? " +
                                "Agent roles using it keep working but become non-sendable until another preset is attached."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { actions.onDeletePreset(dialogState.preset.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = actions::onCancelDialog) {
                        Text("Cancel")
                    }
                }
            )
        }

        ModelPresetDialogState.None -> { /* No dialog */ }
    }
}
