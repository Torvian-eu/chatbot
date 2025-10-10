package eu.torvian.chatbot.app.compose.admin.usergroups

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.admin.GroupFormState
import eu.torvian.chatbot.common.models.user.UserGroup

/**
 * Dialog for editing an existing user group.
 *
 * Displays a form with text fields for group name and description, with validation
 * error messages. The submit button is disabled when the form is invalid or
 * loading, and shows a loading indicator during submission.
 *
 * @param group The group being edited
 * @param formState The current state of the edit form including field values and validation errors
 * @param onDismiss Callback invoked when the dialog is dismissed
 * @param onConfirm Callback invoked when the save button is clicked
 * @param onNameChange Callback invoked when the name field changes
 * @param onDescriptionChange Callback invoked when the description field changes
 */
@Composable
fun EditGroupDialog(
    group: UserGroup,
    formState: GroupFormState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit User Group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Name field
                OutlinedTextField(
                    value = formState.name,
                    onValueChange = onNameChange,
                    label = { Text("Group Name") },
                    isError = formState.nameError != null,
                    supportingText = formState.nameError?.let { { Text(it) } },
                    enabled = !formState.isLoading,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Description field
                OutlinedTextField(
                    value = formState.description ?: "",
                    onValueChange = onDescriptionChange,
                    label = { Text("Description (optional)") },
                    enabled = !formState.isLoading,
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )

                // General error
                if (formState.generalError != null) {
                    Text(
                        text = formState.generalError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = formState.isValid && !formState.isLoading
            ) {
                if (formState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !formState.isLoading) {
                Text("Cancel")
            }
        }
    )
}

