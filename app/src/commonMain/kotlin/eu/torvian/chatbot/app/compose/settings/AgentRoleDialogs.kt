package eu.torvian.chatbot.app.compose.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.domain.contracts.AgentRoleDialogState
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.common.models.tool.ToolDefinition

/**
 * Dialog router for the Agent Roles tab.
 *
 * Dispatches to the form or confirmation dialog based on the current [AgentRoleDialogState], and
 * feeds the role form its model/preset/settings/tool catalogs.
 *
 * @param dialogState The current dialog state from the ViewModel.
 * @param actions ViewModel-forwarding actions.
 * @param models Chat-capable models available for the form's `model_specific` instruction targets.
 * @param presets The user's model presets (name-ascending) offered by the form's preset picker.
 * @param settingsById Settings lookup used by the form's non-sendability hint to resolve the profile a
 *            preset references.
 * @param tools Enabled tools available for the form's multi-select.
 * @param roles Same-user roles available as spawn targets.
 * @param projects Same-user projects available for the form's single project selector (a role
 *            belongs to at most one project; null = unassociated).
 */
@Composable
fun AgentRoleDialogs(
    dialogState: AgentRoleDialogState,
    actions: AgentRolesTabActions,
    models: List<LLMModel>,
    presets: List<ModelPresetDto>,
    settingsById: Map<Long, ModelSettings>,
    tools: List<ToolDefinition>,
    roles: List<AgentRoleDto>,
    projects: List<ProjectDto>
) {
    when (dialogState) {
        is AgentRoleDialogState.AddRole -> {
            AgentRoleFormDialog(
                title = "Add Agent Role",
                formState = dialogState.formState,
                models = models,
                presets = presets,
                settingsById = settingsById,
                tools = tools,
                roles = roles,
                projects = projects,
                onFormUpdate = actions::onUpdateRoleForm,
                onSave = actions::onSaveRole,
                onCancel = actions::onCancelDialog
            )
        }

        is AgentRoleDialogState.EditRole -> {
            AgentRoleFormDialog(
                title = "Edit Agent Role",
                formState = dialogState.formState,
                models = models,
                presets = presets,
                settingsById = settingsById,
                tools = tools,
                roles = roles,
                projects = projects,
                onFormUpdate = actions::onUpdateRoleForm,
                onSave = actions::onSaveRole,
                onCancel = actions::onCancelDialog
            )
        }

        is AgentRoleDialogState.DeleteRole -> {
            AlertDialog(
                onDismissRequest = actions::onCancelDialog,
                title = { Text("Delete Agent Role") },
                text = {
                    Text(
                        "Are you sure you want to delete the role '${dialogState.role.displayName ?: dialogState.role.name}'? " +
                                "Sessions using it will be unassigned and become inert until another role is selected."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { actions.onDeleteRole(dialogState.role.id) },
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

        AgentRoleDialogState.None -> { /* No dialog */ }
    }
}
