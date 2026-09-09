package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.torvian.chatbot.app.compose.common.ConfigTextField
import eu.torvian.chatbot.app.domain.contracts.ProjectFormState

/**
 * Small dialog for cloning a project.
 *
 * Only the new name (prefilled `Copy of <name>` per Q4-A) and an optional description override
 * (prefilled from the source) are editable: the member roles are deep-copied server-side and are not
 * configurable here. The name field reuses the same validation as the create/edit form
 * ([ProjectFormState.validate], enforced by the ViewModel), so a blank or overlong name blocks the
 * clone and surfaces its validation message.
 *
 * @param formState The current clone draft (name + description).
 * @param onFormUpdate Applies an update function to the clone draft.
 * @param onClone Starts the clone with the current draft.
 * @param onCancel Cancels the dialog.
 */
@Composable
fun CloneProjectDialog(
    formState: ProjectFormState,
    onFormUpdate: ((ProjectFormState) -> ProjectFormState) -> Unit,
    onClone: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Card(modifier = Modifier.widthIn(min = 480.dp, max = 560.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Clone Project", style = MaterialTheme.typography.headlineSmall)

                Text(
                    text = "The project's member agent roles are deep-copied into the clone. " +
                        "The source project is left untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ConfigTextField(
                    value = formState.name,
                    onValueChange = { value -> onFormUpdate { it.copy(name = value) } },
                    label = "Project Name *",
                    isError = formState.name.isBlank()
                )
                ConfigTextField(
                    value = formState.description,
                    onValueChange = { value -> onFormUpdate { it.copy(description = value) } },
                    label = "Description",
                    singleLine = false,
                    modifier = Modifier.height(80.dp)
                )

                // Validation error surfaced by the ViewModel (same rule as the create/edit form).
                formState.errorMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onClone,
                        enabled = formState.name.isNotBlank()
                    ) {
                        Text("Clone")
                    }
                }
            }
        }
    }
}