package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.AgentRoleSection
import eu.torvian.chatbot.app.domain.contracts.pickerLabel

/**
 * Picker of agent roles grouped into project sections, shared by the agent-role form and the
 * credential-profile editor.
 *
 * Every section renders its title above a row of toggleable chips labelled by
 * [pickerLabel], so two surfaces cannot drift apart in grouping or wording. The picker is
 * stateless: the selection and the toggle are supplied by the caller.
 *
 * @param sections Grouped roles in display order, as produced by a section builder.
 * @param selectedRoleIds Ids currently selected.
 * @param onToggleRole Called with the id of the chip the operator clicks.
 * @param modifier Modifier applied to the whole picker.
 */
@Composable
fun AgentRoleChipPicker(
    sections: List<AgentRoleSection>,
    selectedRoleIds: Set<Long>,
    onToggleRole: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Headers and chip rows are flat children of one 16.dp column: a header sits as close to its own
    // chips as one section sits to the next, so the grouped picker stays evenly spaced.
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        sections.forEach { section ->
            Text(
                text = section.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                section.roles.forEach { role ->
                    FilterChip(
                        selected = role.id in selectedRoleIds,
                        onClick = { onToggleRole(role.id) },
                        label = { Text(role.pickerLabel) }
                    )
                }
            }
        }
    }
}
