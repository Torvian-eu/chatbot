package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.app.compose.common.StatusBadge
import eu.torvian.chatbot.app.domain.contracts.InstructionUsage
import eu.torvian.chatbot.app.domain.contracts.assignableRoles
import eu.torvian.chatbot.app.domain.contracts.roleWithProjectLabel
import eu.torvian.chatbot.app.domain.contracts.usage
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.modelSpecificId
import eu.torvian.chatbot.common.models.agent.shared
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * Full-width details page for a single instruction.
 *
 * Shows the stored content, whether the row is shared, and which of the user's roles link it (the
 * usage list, labelled from the role catalog the app already loads). The page owns the two link
 * actions: unassigning one role and assigning the row to another role, which appends it last in that
 * role's instruction list. It also opens the content editor, which edits the shared row itself, so the
 * change reaches every linked role. There are deliberately no reordering controls — a role's instruction
 * order is edited in the agent-role editor.
 *
 * The server refuses to delete a row that any role links, so the Delete action is disabled while the
 * row is linked and the usage section explains how to make it deletable; the server's conflict answer
 * remains the backstop for a stale cache.
 *
 * A `spawnable_agents` row has no stored text, so the page states that its message is generated per
 * linked role instead of showing an empty message.
 *
 * @param instruction The instruction to display.
 * @param roles The user's roles, offered as assignment targets.
 * @param rolesById Role lookup used to label the usage list.
 * @param projectsById Project lookup qualifying every role label with its project name.
 * @param onBackToList Callback invoked when the user returns to the instruction list.
 * @param onEdit Callback invoked when the user starts editing the instruction's content.
 * @param onDelete Callback invoked when the user starts deleting the instruction.
 * @param onAssignToRole Callback invoked to link the instruction to a role.
 * @param onUnassignFromRole Callback invoked to unlink the instruction from a role.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun InstructionDetailPage(
    instruction: AgentInstructionDto,
    roles: List<AgentRoleDto>,
    rolesById: Map<Long, AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>,
    onBackToList: () -> Unit,
    onEdit: (AgentInstructionDto) -> Unit,
    onDelete: (AgentInstructionDto) -> Unit,
    onAssignToRole: (roleId: Long) -> Unit,
    onUnassignFromRole: (roleId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val usage = instruction.usage(rolesById, projectsById)
    val assignableRoles = instruction.assignableRoles(roles, projectsById)
    // A linked row cannot be deleted server-side, so the affordance is withheld rather than offered
    // and refused.
    val canDelete = instruction.linkedRoleIds.isEmpty()

    SettingsDetailPage(
        categoryName = SettingsCategory.Instructions.displayLabel,
        itemName = instruction.name,
        supportingText = instruction.type,
        onBackToList = onBackToList,
        backContentDescription = "Back to instructions",
        modifier = modifier,
        actions = {
            TextButton(onClick = { onEdit(instruction) }) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit")
            }
            TextButton(
                onClick = { onDelete(instruction) },
                enabled = canDelete,
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (instruction.shared) {
                    SharedInstructionBadge()
                }
                Text(
                    text = usedByLabel(instruction, rolesById, projectsById),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DetailRow(
                label = "Message",
                value = instruction.message.ifBlank { generatedMessageNote(instruction) }
            )

            if (instruction.type == AgentInstructionTypes.MODEL_SPECIFIC) {
                // The target model is what makes the row apply at all; the id is what the stored custom
                // data carries, and the agent-role editor is where the target is chosen.
                DetailRow(label = "Applies to model", value = "Model #${instruction.modelSpecificId()}")
            }

            InstructionUsageSection(
                usage = usage,
                canDelete = canDelete,
                onUnassignFromRole = onUnassignFromRole
            )

            InstructionAssignSection(
                assignableRoles = assignableRoles,
                projectsById = projectsById,
                onAssignToRole = onAssignToRole
            )
        }
    }
}

/**
 * The usage list: one row per role that links the instruction, each with an unassign action.
 *
 * @param usage The linking roles, already labelled for display.
 * @param canDelete Whether the row may be deleted, i.e. no role links it any more.
 * @param onUnassignFromRole Callback invoked with the role id to unlink.
 */
@Composable
private fun InstructionUsageSection(
    usage: List<InstructionUsage>,
    canDelete: Boolean,
    onUnassignFromRole: (roleId: Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailLabel("Used by roles")

        if (usage.isEmpty()) {
            Text(
                text = "No role uses this instruction. It stays in the library as an unassigned entry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            usage.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = { onUnassignFromRole(entry.roleId) }) {
                        Text("Unassign")
                    }
                }
            }
        }

        if (!canDelete) {
            Text(
                text = "This instruction cannot be deleted while a role uses it. Unassign it from " +
                        "every role above, or delete those roles, first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The assignment control: a role picker plus the assign action.
 *
 * The picker offers only the roles that do not link the instruction yet, because a role links a row at
 * most once. Assigning appends the instruction last in the role's list, which the section states so
 * the append-position rule is visible where it applies.
 *
 * @param assignableRoles The roles that can still be linked, in project-then-label order.
 * @param projectsById Project lookup qualifying every option label with its project name.
 * @param onAssignToRole Callback invoked with the selected role id.
 */
@Composable
private fun InstructionAssignSection(
    assignableRoles: List<AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>,
    onAssignToRole: (roleId: Long) -> Unit
) {
    var selectedRole by remember(assignableRoles) { mutableStateOf(assignableRoles.firstOrNull()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailLabel("Assign to role")

        if (assignableRoles.isEmpty()) {
            Text(
                text = "Every role already uses this instruction.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConfigDropdown(
                    selectedItem = selectedRole,
                    onItemSelected = { selectedRole = it },
                    items = assignableRoles,
                    label = "Role",
                    modifier = Modifier.weight(1f),
                    itemText = { it.roleWithProjectLabel(projectsById) }
                )
                Button(
                    onClick = { selectedRole?.let { onAssignToRole(it.id) } },
                    enabled = selectedRole != null
                ) {
                    Text("Assign")
                }
            }

            Text(
                text = "The instruction is appended last in the role's instruction list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Badge marking an instruction that more than one agent role links.
 *
 * Shared content is edited in one place and reaches every linked role, so the marker is shown wherever
 * a row is reported.
 */
@Composable
fun SharedInstructionBadge(modifier: Modifier = Modifier) {
    StatusBadge(
        text = "Shared",
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier
    )
}
