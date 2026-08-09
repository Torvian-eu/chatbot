package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.app.compose.settings.dialogs.ManageAccessDialog
import eu.torvian.chatbot.app.domain.contracts.ModelSettingsDialogState
import eu.torvian.chatbot.app.domain.contracts.getSupportedSettingsTypes
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Dialog router for the Model Settings tab.
 * Displays the appropriate dialog based on the current dialog state.
 */
@Composable
fun SettingsDialogs(
    dialogState: ModelSettingsDialogState,
    actions: ModelSettingsConfigTabActions
) {
    when (dialogState) {
        is ModelSettingsDialogState.ChooseNewSettingsType -> {
            ChooseNewSettingsTypeDialog(
                onConfirm = actions::onChooseNewSettingsType,
                onDismiss = actions::onCancelDialog
            )
        }

        is ModelSettingsDialogState.AddNewSettings -> {
            ModelSettingsFormDialog(
                title = "Add New Settings Profile",
                formState = dialogState.formState,
                onFormUpdate = actions::onUpdateSettingsForm,
                onSave = actions::onSaveSettings,
                onCancel = actions::onCancelDialog
            )
        }

        is ModelSettingsDialogState.EditSettings -> {
            ModelSettingsFormDialog(
                title = "Edit Settings Profile",
                formState = dialogState.formState,
                onFormUpdate = actions::onUpdateSettingsForm,
                onSave = actions::onSaveSettings,
                onCancel = actions::onCancelDialog
            )
        }

        is ModelSettingsDialogState.DeleteSettings -> {
            DeleteModelSettingsDialog(
                settings = dialogState.settings,
                onConfirm = { actions.onDeleteSettings(dialogState.settings.id) },
                onDismiss = actions::onCancelDialog
            )
        }

        is ModelSettingsDialogState.ManageAccess -> {
            ManageAccessDialog(
                resourceName = dialogState.settingsDetails.settings.name,
                accessDetails = dialogState.settingsDetails.accessDetails,
                availableGroups = dialogState.availableGroups,
                showGrantDialog = dialogState.showGrantDialog,
                grantAccessForm = dialogState.grantAccessForm,
                onOpenGrantDialog = actions::onOpenGrantAccessDialog,
                onCloseGrantDialog = actions::onCloseGrantAccessDialog,
                onUpdateGrantForm = actions::onUpdateGrantAccessForm,
                onConfirmGrant = { groupId, accessMode ->
                    actions.onGrantSettingsAccess(
                        dialogState.settingsDetails.settings.id,
                        groupId,
                        accessMode
                    )
                },
                onRevokeAccess = { groupId, accessMode ->
                    actions.onRevokeSettingsAccess(
                        dialogState.settingsDetails.settings.id,
                        groupId,
                        accessMode
                    )
                },
                onDismiss = actions::onCancelDialog
            )
        }

        is ModelSettingsDialogState.None -> { /* No dialog */
        }
    }
}

/**
 * Dialog that asks the user which settings type (API dialect) the new profile should describe.
 * A model carries no operational type of its own, so the type must be chosen explicitly.
 *
 * @param onConfirm Invoked with the chosen [LLMModelType].
 * @param onDismiss Invoked when the user cancels.
 */
@Composable
private fun ChooseNewSettingsTypeDialog(
    onConfirm: (LLMModelType) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedType by remember { mutableStateOf(LLMModelType.RESPONSES) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Settings Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose the type of settings profile to create. A model can have multiple " +
                            "profiles of different types (e.g. Chat Completions, Responses API, Embeddings)."
                )
                ConfigDropdown(
                    selectedItem = selectedType,
                    onItemSelected = { selectedType = it },
                    items = getSupportedSettingsTypes(),
                    label = "Settings Type",
                    itemText = { it.name }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedType) }) {
                Text("Continue")
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
 * Confirmation dialog for deleting settings profiles.
 */
@Composable
private fun DeleteModelSettingsDialog(
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
