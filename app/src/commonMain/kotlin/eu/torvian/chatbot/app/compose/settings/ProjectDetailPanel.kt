package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * Full-width details page for a single project.
 *
 * Shows the project's description and its member roles, resolved from `ProjectDto.agentRoleIds`
 * through [rolesById]. Unknown role ids (deleted server-side or drifted) render as "unknown role".
 *
 * @param project The project to display.
 * @param rolesById Role lookup for the project's member role ids.
 * @param onBackToList Callback invoked when the user returns to the project list.
 * @param onEdit Callback invoked when the user starts editing the project.
 * @param onClone Callback invoked when the user starts cloning the project (opens the clone dialog).
 * @param onDelete Callback invoked when the user starts deleting the project.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ProjectDetailPage(
    project: ProjectDto,
    rolesById: Map<Long, AgentRoleDto>,
    onBackToList: () -> Unit,
    onEdit: (ProjectDto) -> Unit,
    onClone: (ProjectDto) -> Unit,
    onDelete: (ProjectDto) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsDetailPage(
        categoryName = SettingsCategory.Projects.displayLabel,
        itemName = project.name,
        onBackToList = onBackToList,
        backContentDescription = "Back to projects",
        modifier = modifier,
        actions = {
            TextButton(onClick = { onEdit(project) }) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit")
            }
            TextButton(onClick = { onClone(project) }) {
                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clone")
            }
            TextButton(
                onClick = { onDelete(project) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete")
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (project.description.isNotBlank()) {
                DetailRow(label = "Description", value = project.description)
            }

            HorizontalDivider()

            Text(
                text = "Member roles",
                style = MaterialTheme.typography.titleMedium
            )

            if (project.agentRoleIds.isEmpty()) {
                Text(
                    text = "No roles in this project. Edit the project or a role to change its membership.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                project.agentRoleIds.forEach { roleId ->
                    val role = rolesById[roleId]
                    DetailRow(
                        label = "Role",
                        value = role?.let { it.displayName?.takeIf { name -> name.isNotBlank() } ?: it.name }
                            ?: "Role #$roleId (not found)"
                    )
                }
            }
        }
    }
}