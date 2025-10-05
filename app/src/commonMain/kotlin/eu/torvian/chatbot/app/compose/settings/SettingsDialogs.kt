package eu.torvian.chatbot.app.compose.settings

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.domain.contracts.SettingsDialogState
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Dialog router for the Settings Config tab.
 * Displays the appropriate dialog based on the current dialog state.
 */
@Composable
fun SettingsDialogs(
    dialogState: SettingsDialogState,
    actions: SettingsConfigTabActions
) {
    when (dialogState) {
        is SettingsDialogState.AddNewSettings -> {
            SettingsFormDialog(
                title = "Add New Settings Profile",
                formState = dialogState.formState,
                onFormUpdate = actions::onUpdateSettingsForm,
                onSave = actions::onSaveSettings,
                onCancel = actions::onCancelDialog
            )
        }
        is SettingsDialogState.EditSettings -> {
            SettingsFormDialog(
                title = "Edit Settings Profile",
                formState = dialogState.formState,
                onFormUpdate = actions::onUpdateSettingsForm,
                onSave = actions::onSaveSettings,
                onCancel = actions::onCancelDialog
            )
        }
        is SettingsDialogState.DeleteSettings -> {
            DeleteSettingsDialog(
                settings = dialogState.settings,
                onConfirm = { actions.onDeleteSettings(dialogState.settings.id) },
                onDismiss = actions::onCancelDialog
            )
        }
        is SettingsDialogState.None -> { /* No dialog */ }
    }
}

/**
 * Confirmation dialog for deleting settings profiles.
 */
@Composable
private fun DeleteSettingsDialog(
    settings: ModelSettings,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Settings Profile") },
        text = {
            Text("Are you sure you want to delete the profile '${settings.name}'? This action cannot be undone.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
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
