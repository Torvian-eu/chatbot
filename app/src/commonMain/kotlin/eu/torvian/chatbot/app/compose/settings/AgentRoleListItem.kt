package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.agent.AgentRoleDto

/**
 * Compact card used for a single agent-role row in the management list.
 *
 * The row shows the role's core metadata plus a trailing enable/disable [Switch] (checked = enabled
 * for the current user). The switch is its own click target: flipping it toggles the role's per-user
 * disabled state without navigating to the detail page, while clicking anywhere else on the row still
 * opens the detail page. Disabled roles are dimmed and labeled "Disabled" as a visual affordance so
 * the user can tell them apart while still being able to re-enable them (the list keeps showing
 * disabled roles — only the chat selector filters them out).
 *
 * @param role Role shown in the row.
 * @param isSelected Whether the row is visually focused.
 * @param onToggleDisabled Callback invoked with the role when the switch is flipped; the caller
 *            decides the new state (typically `!role.disabled`).
 * @param onClick Callback invoked when the row is activated.
 * @param modifier Modifier applied to the row card.
 */
@Composable
fun AgentRoleListItem(
    role: AgentRoleDto,
    isSelected: Boolean,
    onToggleDisabled: (AgentRoleDto) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = backgroundColor,
        shadowElevation = if (isSelected) 4.dp else 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = role.displayName?.takeIf { it.isNotBlank() } ?: role.name,
                    style = MaterialTheme.typography.titleMedium,
                    // Dim the label of disabled roles so the enabled/disabled state is readable at a
                    // glance; the switch remains interactive so the role can be re-enabled.
                    color = if (role.disabled) contentColor.copy(alpha = 0.55f) else contentColor
                )

                if (role.description.isNotBlank()) {
                    Text(
                        text = role.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.8f),
                        maxLines = 2
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${role.tools.size} tool(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${role.instructions.size} instruction(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (role.disabled) {
                        Text(
                            text = "Disabled",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Switch(
                checked = !role.disabled,
                onCheckedChange = { onToggleDisabled(role) }
            )
        }
    }
}
