package eu.torvian.chatbot.app.compose.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.domain.contracts.ProjectDialogState
import eu.torvian.chatbot.common.models.agent.AgentRoleDto

/**
 * Dialog router for the Projects tab.
 *
 * Dispatches to the form or confirmation dialog based on the current [ProjectDialogState], and feeds
 * the project form its role catalog.
 *
 * @param dialogState The current dialog state from the ViewModel.
 * @param actions ViewModel-forwarding actions.
 * @param roles Same-user roles available for the form's member-role multi-select.
 */
@Composable
fun ProjectDialogs(
    dialogState: ProjectDialogState,
    actions: ProjectsTabActions,
    roles: List<AgentRoleDto>
) {
    when (dialogState) {
        is ProjectDialogState.AddProject -> {
            ProjectFormDialog(
                title = "Add Project",
                formState = dialogState.formState,
                roles = roles,
                onFormUpdate = actions::onUpdateProjectForm,
                onSave = actions::onSaveProject,
                onCancel = actions::onCancelDialog
            )
        }

        is ProjectDialogState.EditProject -> {
            ProjectFormDialog(
                title = "Edit Project",
                formState = dialogState.formState,
                roles = roles,
                onFormUpdate = actions::onUpdateProjectForm,
                onSave = actions::onSaveProject,
                onCancel = actions::onCancelDialog
            )
        }

        is ProjectDialogState.DeleteProject -> {
            AlertDialog(
                onDismissRequest = actions::onCancelDialog,
                title = { Text("Delete Project") },
                text = {
                    Text(
                        "Are you sure you want to delete the project '${dialogState.project.name}'? " +
                                "Its member roles are kept; sessions using the project will be unassigned " +
                                "from it and become project-less."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { actions.onDeleteProject(dialogState.project.id) },
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

        ProjectDialogState.None -> { /* No dialog */ }
    }
}