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
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition

/**
 * Dialog router for the Agent Roles tab.
 *
 * Dispatches to the form or confirmation dialog based on the current [AgentRoleDialogState], and
 * feeds the role form its model/settings/tool catalogs.
 *
 * @param dialogState The current dialog state from the ViewModel.
 * @param actions ViewModel-forwarding actions.
 * @param models Chat-capable models available for the form.
 * @param settingsForModel Chat-capable settings for the model currently chosen in the form.
 * @param tools Enabled tools available for the form's multi-select.
 * @param roles Same-user roles available as spawn targets.
 */
@Composable
fun AgentRoleDialogs(
    dialogState: AgentRoleDialogState,
    actions: AgentRolesTabActions,
    models: List<LLMModel>,
    settingsForModel: List<ModelSettings>?,
    tools: List<ToolDefinition>,
    roles: List<AgentRoleDto>
) {
    when (dialogState) {
        is AgentRoleDialogState.AddRole -> {
            AgentRoleFormDialog(
                title = "Add Agent Role",
                formState = dialogState.formState,
                models = models,
                settingsForModel = settingsForModel.orEmpty(),
                tools = tools,
                roles = roles,
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
                settingsForModel = settingsForModel.orEmpty(),
                tools = tools,
                roles = roles,
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
