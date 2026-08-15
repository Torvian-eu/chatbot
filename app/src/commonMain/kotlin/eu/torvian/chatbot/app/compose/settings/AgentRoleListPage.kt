package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.agent.AgentRoleDto

/**
 * Full-width page for browsing the user's agent roles.
 *
 * The page owns the shared shell, header copy and add action while [AgentRoleListItem] renders
 * individual rows.
 *
 * @param roles Roles to render in the list.
 * @param selectedRole Currently focused role, used only for row highlighting.
 * @param onRoleSelected Callback invoked when the user opens a role detail page.
 * @param onAddNewRole Callback invoked when the user starts the add-role flow.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun AgentRoleListPage(
    roles: List<AgentRoleDto>,
    selectedRole: AgentRoleDto?,
    onRoleSelected: (AgentRoleDto) -> Unit,
    onAddNewRole: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsListPageTemplate(
        title = "Agent Roles",
        subtitle = if (roles.isEmpty()) {
            "No agent roles yet. Use the add action to create your first role."
        } else {
            "${roles.size} role(s) • select a role to view or edit its configuration."
        },
        modifier = modifier,
        actions = {
            FilledTonalButton(onClick = onAddNewRole) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add role", maxLines = 1, softWrap = false)
            }
        }
    ) {
        if (roles.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No agent roles configured yet.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Create a role to bundle a model, settings, tools and instructions into a single selectable chat persona.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                roles.forEach { role ->
                    AgentRoleListItem(
                        role = role,
                        isSelected = selectedRole?.id == role.id,
                        onClick = { onRoleSelected(role) }
                    )
                }
            }
        }
    }
}
