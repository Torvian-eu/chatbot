package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFilter
import eu.torvian.chatbot.app.domain.contracts.AgentRoleSection
import eu.torvian.chatbot.app.domain.contracts.displayLabel
import eu.torvian.chatbot.app.domain.contracts.filterAgentRoleSections
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * Full-width page for browsing the user's agent roles, grouped by project scope.
 *
 * The page owns the shared shell, header copy and add action. Below the header a project filter
 * ([ConfigDropdown], default "All projects") narrows the grouped sections to a single project or to
 * unassociated roles; selecting a scope with no roles shows an informative empty message while the
 * "Add role" action stays available. [AgentRoleListItem] renders individual rows; section headers
 * are static and always expanded.
 *
 * @param sections Roles grouped by project scope (project sections first, then "No project").
 * @param projects The user's projects, used to build the filter options.
 * @param selectedRole Currently focused role, used only for row highlighting.
 * @param onRoleSelected Callback invoked when the user opens a role detail page.
 * @param onToggleRoleDisabled Callback invoked when the user flips a role's enable/disable switch.
 * @param onAddNewRole Callback invoked when the user starts the add-role flow.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun AgentRoleListPage(
    sections: List<AgentRoleSection>,
    projects: List<ProjectDto>,
    selectedRole: AgentRoleDto?,
    onRoleSelected: (AgentRoleDto) -> Unit,
    onToggleRoleDisabled: (AgentRoleDto) -> Unit,
    onAddNewRole: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalRoles = sections.sumOf { it.roles.size }
    // The filter is local UI state, preserved across tab switches and process recreation. It stores
    // project ids (not names), so renaming a project keeps the selection valid; a deleted project
    // simply matches no section and renders the per-scope empty message.
    var filter by rememberSaveable { mutableStateOf<AgentRoleFilter>(AgentRoleFilter.AllProjects) }
    val visibleSections = filterAgentRoleSections(sections, filter)

    // Projects are sorted by name so the dropdown mirrors the section order (FR-9).
    val projectsByName = projects.sortedBy { it.name }
    val filterOptions: List<AgentRoleFilter> = buildList {
        add(AgentRoleFilter.AllProjects)
        projectsByName.forEach { project -> add(AgentRoleFilter.Project(project.id)) }
        // "No project" is offered whenever the section exists, and also when roles are unassociated
        // but no project sections exist (no-projects edge case) — matching the role-form picker.
        if (sections.any { it.projectId == null }) {
            add(AgentRoleFilter.NoProject)
        }
    }

    SettingsListPageTemplate(
        title = "Agent Roles",
        subtitle = if (totalRoles == 0) {
            "No agent roles yet. Use the add action to create your first role."
        } else {
            "$totalRoles role(s) • select a role to view or edit its configuration."
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
        if (totalRoles == 0) {
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
            if (projects.isNotEmpty() || sections.any { it.projectId == null }) {
                ConfigDropdown(
                    selectedItem = filter,
                    onItemSelected = { filter = it },
                    items = filterOptions,
                    label = "Filter by project",
                    modifier = Modifier.fillMaxWidth(),
                    itemText = { option ->
                        when (option) {
                            AgentRoleFilter.AllProjects -> option.displayLabel
                            is AgentRoleFilter.Project -> projectsByName.firstOrNull { it.id == option.projectId }?.name
                                ?: option.displayLabel

                            AgentRoleFilter.NoProject -> option.displayLabel
                        }
                    }
                )
            }
            if (visibleSections.isEmpty()) {
                // A scope with no roles: keep the header/filter visible and explain the empty scope
                // instead of reusing the global no-roles copy (the user may have roles elsewhere).
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "No agent roles in this scope.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Select a different project filter or create a new role.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleSections.forEach { section ->
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        section.roles.forEach { role ->
                            AgentRoleListItem(
                                role = role,
                                isSelected = selectedRole?.id == role.id,
                                onToggleDisabled = onToggleRoleDisabled,
                                onClick = { onRoleSelected(role) }
                            )
                        }
                    }
                }
            }
        }
    }
}
