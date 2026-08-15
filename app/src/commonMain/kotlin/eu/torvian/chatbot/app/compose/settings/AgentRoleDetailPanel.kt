package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition

/**
 * Full-width details page for a single agent role.
 *
 * Shows the role's model/settings/tools and its resolved instruction list. Unknown instruction
 * types are rendered generically to stay forward compatible with future instruction kinds.
 *
 * @param role The role to display.
 * @param modelsById Model lookup for the role's model id.
 * @param settingsById Settings lookup for the role's settings id.
 * @param toolsById Tool lookup for the role's tool ids.
 * @param onBackToList Callback invoked when the user returns to the role list.
 * @param onEdit Callback invoked when the user starts editing the role.
 * @param onDelete Callback invoked when the user starts deleting the role.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun AgentRoleDetailPage(
    role: AgentRoleDto,
    modelsById: Map<Long, LLMModel>,
    settingsById: Map<Long, ModelSettings>,
    toolsById: Map<Long, ToolDefinition>,
    onBackToList: () -> Unit,
    onEdit: (AgentRoleDto) -> Unit,
    onDelete: (AgentRoleDto) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsDetailPage(
        categoryName = SettingsCategory.AgentRoles.displayLabel,
        itemName = role.displayName?.takeIf { it.isNotBlank() } ?: role.name,
        supportingText = role.name.takeIf { it != (role.displayName?.takeIf { it.isNotBlank() } ?: role.name) },
        onBackToList = onBackToList,
        backContentDescription = "Back to agent roles",
        modifier = modifier,
        actions = {
            TextButton(onClick = { onEdit(role) }) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit")
            }
            TextButton(
                onClick = { onDelete(role) },
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
            if (role.description.isNotBlank()) {
                DetailRow(label = "Description", value = role.description)
            }

            val model = role.modelId?.let { modelsById[it] }
            val settings = role.modelSettingsId?.let { settingsById[it] }
            DetailRow(
                label = "Model",
                value = model?.let { it.displayName ?: it.name } ?: "Not available (repair this role)"
            )
            DetailRow(
                label = "Settings",
                value = settings?.name ?: "Not available (repair this role)"
            )

            val tools = role.tools.mapNotNull { toolsById[it] }
            DetailRow(
                label = "Tools",
                value = if (tools.isEmpty()) "None" else tools.joinToString { it.name }
            )

            HorizontalDivider()

            Text(
                text = "Instructions",
                style = MaterialTheme.typography.titleMedium
            )

            if (role.instructions.isEmpty()) {
                Text(
                    text = "No instructions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                role.instructions.forEachIndexed { index, instruction ->
                    InstructionCard(
                        index = index + 1,
                        type = instruction.type,
                        name = instruction.name,
                        message = instruction.message,
                        isReadOnly = instruction.type == AgentInstructionTypes.MODEL_SETTINGS
                    )
                }
            }
        }
    }
}

/**
 * Card rendering a single instruction entry with its type tag and resolved message.
 *
 * @param index One-based position in the instruction list.
 * @param type Well-known or custom instruction type key.
 * @param name Human-readable instruction label.
 * @param message Resolved instruction text.
 * @param isReadOnly Whether the entry is bound server-side (model settings) and thus not editable.
 * @param modifier Modifier applied to the card.
 */
@Composable
private fun InstructionCard(
    index: Int,
    type: String,
    name: String,
    message: String,
    isReadOnly: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReadOnly) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$index. $name",
                    style = MaterialTheme.typography.titleSmall
                )
                if (isReadOnly) {
                    Text(
                        text = "auto (model settings)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = message.ifBlank { "(empty)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
