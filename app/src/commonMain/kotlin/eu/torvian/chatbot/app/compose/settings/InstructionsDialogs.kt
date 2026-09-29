package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ConfigTextField
import eu.torvian.chatbot.app.compose.common.StatusBadge
import eu.torvian.chatbot.app.domain.contracts.InstructionsDialogState
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.shared

/**
 * Dialog router for the Instructions tab.
 *
 * Dispatches to the confirmation dialog based on the current [InstructionsDialogState]. Only a row no
 * agent role links reaches the delete confirmation, so it states the content loss alone.
 *
 * @param dialogState The current dialog state from the ViewModel.
 * @param actions ViewModel-forwarding actions.
 */
@Composable
fun InstructionsDialogs(
    dialogState: InstructionsDialogState,
    actions: InstructionsTabActions
) {
    when (dialogState) {
        is InstructionsDialogState.DeleteInstruction -> {
            AlertDialog(
                onDismissRequest = actions::onCancelDialog,
                title = { Text("Delete Instruction") },
                text = {
                    Text(
                        "Are you sure you want to delete '${dialogState.instruction.name}'? " +
                                "No agent role uses it, and its content cannot be restored."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { actions.onDeleteInstruction(dialogState.instruction.id) },
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

        is InstructionsDialogState.EditInstruction -> InstructionEditDialog(
            state = dialogState,
            actions = actions
        )

        InstructionsDialogState.None -> { /* No dialog */ }
    }
}

/**
 * Content editor for one library instruction.
 *
 * Edits the authored label and text, which is what makes a row shared: saving rewrites the row, so every
 * role that links it reports the new content at its next read. The kind (and a `model_specific` row's
 * target model) is stated rather than editable, because changing it can invalidate a linked role's
 * instruction list, and only the agent-role editor knows that list.
 *
 * A row of the generated-message kind stores no text, so its message field is read-only and the dialog
 * states where the text comes from instead.
 *
 * @param state The open edit form.
 * @param actions ViewModel-forwarding actions.
 */
@Composable
private fun InstructionEditDialog(
    state: InstructionsDialogState.EditInstruction,
    actions: InstructionsTabActions
) {
    val isMessageReadOnly = state.instruction.type == AgentInstructionTypes.SPAWNABLE_AGENTS

    AlertDialog(
        onDismissRequest = actions::onCancelDialog,
        title = { Text("Edit Instruction") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusBadge(text = state.instruction.type)

                ConfigTextField(
                    value = state.name,
                    onValueChange = actions::onEditInstructionNameChanged,
                    label = "Name",
                    isError = state.isNameBlank,
                    errorMessage = "Name cannot be blank"
                )

                ConfigTextField(
                    value = state.message,
                    onValueChange = actions::onEditInstructionMessageChanged,
                    label = "Message",
                    singleLine = false,
                    minLines = 12,
                    maxLines = 16,
                    enabled = !isMessageReadOnly
                )

                if (isMessageReadOnly) {
                    Text(
                        text = "This instruction stores no text: each linked role resolves its own message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (state.instruction.shared) {
                    Text(
                        text = "This instruction is used by more than one agent role, so the change applies to all of them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = if (state.instruction.type == AgentInstructionTypes.MODEL_SPECIFIC) {
                        "The kind and the target model are edited in the agent-role editor."
                    } else {
                        "The kind is edited in the agent-role editor."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = actions::onSaveInstructionEdit,
                enabled = state.canSave
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = actions::onCancelDialog) {
                Text("Cancel")
            }
        }
    )
}
