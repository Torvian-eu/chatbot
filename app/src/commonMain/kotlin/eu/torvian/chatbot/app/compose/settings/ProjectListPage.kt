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
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * Full-width page for browsing the user's projects.
 *
 * The page owns the shared shell, header copy and add action while [ProjectListItem] renders
 * individual rows.
 *
 * @param projects Projects to render in the list.
 * @param onProjectSelected Callback invoked when the user opens a project detail page.
 * @param onAddNewProject Callback invoked when the user starts the add-project flow.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ProjectListPage(
    projects: List<ProjectDto>,
    onProjectSelected: (ProjectDto) -> Unit,
    onAddNewProject: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsListPageTemplate(
        title = "Projects",
        subtitle = if (projects.isEmpty()) {
            "No projects yet. Use the add action to create your first project."
        } else {
            "${projects.size} project(s) • select a project to view or edit its member roles."
        },
        modifier = modifier,
        actions = {
            FilledTonalButton(onClick = onAddNewProject) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add project", maxLines = 1, softWrap = false)
            }
        }
    ) {
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No projects configured yet.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Create a project to group agent roles together and filter the session role selector by project.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                projects.forEach { project ->
                    ProjectListItem(
                        project = project,
                        onClick = { onProjectSelected(project) }
                    )
                }
            }
        }
    }
}