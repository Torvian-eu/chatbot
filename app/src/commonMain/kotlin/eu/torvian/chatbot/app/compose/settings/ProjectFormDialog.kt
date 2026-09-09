package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.torvian.chatbot.app.compose.common.ConfigTextField
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.domain.contracts.ProjectFormState
import eu.torvian.chatbot.common.models.agent.AgentRoleDto

/**
 * Form dialog for creating or editing a project.
 *
 * The dialog binds the project's name, description and member-role set. Role membership is a full
 * replacement on save; roles already bound to ANOTHER project are disabled (single-project
 * membership — attaching one would silently move it), and an empty selected set means the project
 * has no member roles.
 *
 * @param title Dialog title ("Add Project" / "Edit Project").
 * @param formState The current form draft.
 * @param roles Same-user roles available for the member-role multi-select.
 * @param onFormUpdate Applies an update function to the form draft.
 * @param onSave Saves the form.
 * @param onCancel Cancels the dialog.
 */
@Composable
fun ProjectFormDialog(
    title: String,
    formState: ProjectFormState,
    roles: List<AgentRoleDto>,
    onFormUpdate: ((ProjectFormState) -> ProjectFormState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Card(modifier = Modifier.widthIn(min = 520.dp, max = 640.dp)) {
            val scrollState = rememberScrollState()
            ScrollbarWrapper(
                scrollState = scrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(24.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

                        // Role membership is a set from the project side, but roles now have
                        // SINGLE-project membership: a role already bound to ANOTHER project is
                        // disabled here (attaching it would silently move it; the server rejects it),
                        // while unassociated roles and roles already in this project stay selectable.
                        Text("Member roles", style = MaterialTheme.typography.titleSmall)
                        if (roles.isEmpty()) {
                            Text(
                                text = "No agent roles are available.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                roles.forEach { role ->
                                    val selected = role.id in formState.agentRoleIds
                                    val belongsElsewhere = role.projectId != null && role.projectId != formState.projectId
                                    FilterChip(
                                        selected = selected,
                                        enabled = !belongsElsewhere,
                                        onClick = {
                                            onFormUpdate { current ->
                                                current.copy(
                                                    agentRoleIds = if (selected) {
                                                        current.agentRoleIds - role.id
                                                    } else {
                                                        current.agentRoleIds + role.id
                                                    }
                                                )
                                            }
                                        },
                                        label = {
                                            val display = role.displayName?.takeIf { it.isNotBlank() }
                                            Text(if (display == null) role.name else "${role.name} — $display", maxLines = 1)
                                        }
                                    )
                                }
                            }
                            val boundElsewhere = roles.any { role ->
                                role.projectId != null && role.projectId != formState.projectId
                            }
                            if (boundElsewhere) {
                                Text(
                                    text = "Roles bound to another project are disabled; detach them there first.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                            Text(if (formState.mode == FormMode.NEW) "Add Project" else "Save Changes")
                        }
                    }
                }
            }
        }
    }
}