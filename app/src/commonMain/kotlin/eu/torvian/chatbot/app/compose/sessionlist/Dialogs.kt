package eu.torvian.chatbot.app.compose.sessionlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.SessionListActions
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.ChatSessionSummary

/**
 * Consolidated dialog management for the session list panel.
 */
@Composable
fun Dialogs(
    dialogState: DialogState,
    allSessions: List<ChatSessionSummary>,
    allGroups: List<ChatGroup>,
    sessionListActions: SessionListActions,
    onDialogStateChange: (DialogState) -> Unit
) {
    when (dialogState) {
        is DialogState.NewSession -> {
            NewSessionDialog(
                nameInput = dialogState.sessionNameInput,
                onNameInputChange = {
                    onDialogStateChange(dialogState.copy(sessionNameInput = it))
                },
                onCreateSession = {
                    sessionListActions.onCreateNewSession(dialogState.sessionNameInput.ifBlank { null })
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.RenameSession -> {
            RenameSessionDialog(
                nameInput = dialogState.newSessionNameInput,
                onNameInputChange = {
                    onDialogStateChange(dialogState.copy(newSessionNameInput = it))
                },
                onRenameSession = {
                    sessionListActions.onRenameSession(dialogState.session, dialogState.newSessionNameInput)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.DeleteSession -> {
            DeleteSessionDialog(
                onDeleteConfirm = {
                    sessionListActions.onDeleteSession(dialogState.sessionId)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.AssignGroup -> {
            AssignSessionToGroupDialog(
                session = allSessions.find { it.id == dialogState.sessionId },
                groups = allGroups,
                onAssignToGroup = { sessionId, groupId ->
                    sessionListActions.onAssignSessionToGroup(sessionId, groupId)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.DeleteGroup -> {
            DeleteGroupDialog(
                onDeleteConfirm = {
                    sessionListActions.onDeleteGroup(dialogState.groupId)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        DialogState.None -> { /* No dialog to show */
        }
    }
}

/**
 * Dialog for creating a new session.
 */
@Composable
private fun NewSessionDialog(
    nameInput: String,
    onNameInputChange: (String) -> Unit,
    onCreateSession: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Chat Session") },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = onNameInputChange,
                label = { Text("Session Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = onCreateSession) {
                Text("Create")
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
 * Dialog for renaming an existing session with validation.
 */
@Composable
private fun RenameSessionDialog(
    nameInput: String,
    onNameInputChange: (String) -> Unit,
    onRenameSession: () -> Unit,
    onDismiss: () -> Unit
) {
    val isValid = nameInput.trim().isNotBlank()
    val hasInput = nameInput.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = onNameInputChange,
                label = { Text("New Session Name") },
                singleLine = true,
                isError = hasInput && !isValid,
                supportingText = if (hasInput && !isValid) {
                    { Text("Session name cannot be empty") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = onRenameSession,
                enabled = isValid
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for confirming session deletion.
 */
@Composable
private fun DeleteSessionDialog(
    onDeleteConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Session?") },
        text = { Text("Are you sure you want to delete this session and all its messages? This action cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onDeleteConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for assigning a session to a group.
 */
@Composable
private fun AssignSessionToGroupDialog(
    session: ChatSessionSummary?,
    groups: List<ChatGroup>,
    onAssignToGroup: (Long, Long?) -> Unit,
    onDismiss: () -> Unit
) {
    if (session != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Assign Session to Group") },
            text = {
                Column {
                    Text("Select a group for '${session.name}':")
                    Spacer(Modifier.height(8.dp))
                    // Option for "Ungrouped"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAssignToGroup(session.id, null)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = session.groupId == null,
                            onClick = null // Handled by row click
                        )
                        Text("Ungrouped")
                    }
                    // Options for existing groups
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAssignToGroup(session.id, group.id)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = session.groupId == group.id,
                                onClick = null // Handled by row click
                            )
                            Text(group.name)
                        }
                    }
                }
            },
            confirmButton = {
                // No explicit confirm button needed, selection triggers action
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Dialog for confirming group deletion.
 */
@Composable
private fun DeleteGroupDialog(
    onDeleteConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Group?") },
        text = { Text("Are you sure you want to delete this group? Any sessions assigned to it will become 'Ungrouped'. This action cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onDeleteConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
