package eu.torvian.chatbot.app.compose.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.domain.contracts.ModelsDialogState

/**
 * Composable for managing all dialogs in the ModelsTab.
 *
 * @param dialogState The current dialog state.
 * @param state The ModelsTab state for accessing model configuration data.
 * @param actions The actions contract for the ModelsTab.
 */
@Composable
fun ModelsDialogs(
    dialogState: ModelsDialogState,
    state: ModelsTabState,
    actions: ModelsTabActions
) {
    when (dialogState) {
        is ModelsDialogState.AddNewModel -> {
            ModelFormDialog(
                title = "Add New Model",
                form = dialogState.formState,
                providers = dialogState.providers,
                onFormUpdate = { update -> actions.onUpdateModelForm(update) },
                onSave = { actions.onSaveModel() },
                onCancel = { actions.onCancelDialog() }
            )
        }

        is ModelsDialogState.EditModel -> {
            ModelFormDialog(
                title = "Edit Model",
                form = dialogState.formState,
                providers = dialogState.providers,
                onFormUpdate = { update -> actions.onUpdateModelForm(update) },
                onSave = { actions.onSaveModel() },
                onCancel = { actions.onCancelDialog() }
            )
        }

        is ModelsDialogState.DeleteModel -> {
            DeleteModelDialog(
                model = dialogState.model,
                onConfirmDelete = {
                    actions.onDeleteModel(dialogState.model.id)
                },
                onDismiss = { actions.onCancelDialog() }
            )
        }

        ModelsDialogState.None -> { /* No dialog to show */ }
    }
}

/**
 * Composable for displaying a delete confirmation dialog for a model.
 *
 * @param model The model for which deletion is being confirmed.
 * @param onConfirmDelete Callback for the confirm delete action.
 * @param onDismiss Callback for the dismiss action.
 */
@Composable
private fun DeleteModelDialog(
    model: eu.torvian.chatbot.common.models.LLMModel,
    onConfirmDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Model") },
        text = {
            Text("Are you sure you want to delete the model '${model.displayName ?: model.name}'? This action cannot be undone.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmDelete
            ) {
                Text("Delete")
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
