package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.StatusBadge
import eu.torvian.chatbot.app.utils.ui.formatRelativeTime
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Full-width details page for a single model preset.
 *
 * Shows the preset's references as *resolved* values (the referenced model and settings profile, with
 * their names and the settings' model type) and an "Incomplete" mark while a reference is null.
 *
 * The page stays a pure reference view: the preset deliberately carries no LLM payload of its own
 * (U-8), and the referenced profile's content is not duplicated here — provider routing and custom
 * parameters belong to the settings profile and are viewed or edited on that profile itself in
 * Settings → Model Settings.
 *
 * @param preset The preset to display.
 * @param modelsById Model lookup used to resolve the preset's model reference.
 * @param settingsById Settings lookup used to resolve the preset's settings reference.
 * @param onBackToList Callback invoked when the user returns to the preset list.
 * @param onEdit Callback invoked when the user starts editing the preset.
 * @param onDelete Callback invoked when the user starts deleting the preset.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ModelPresetDetailPage(
    preset: ModelPresetDto,
    modelsById: Map<Long, LLMModel>,
    settingsById: Map<Long, ModelSettings>,
    onBackToList: () -> Unit,
    onEdit: (ModelPresetDto) -> Unit,
    onDelete: (ModelPresetDto) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsDetailPage(
        categoryName = SettingsCategory.ModelPresets.displayLabel,
        itemName = preset.displayName?.takeIf { it.isNotBlank() } ?: preset.name,
        supportingText = preset.name.takeIf { it != (preset.displayName?.takeIf { name -> name.isNotBlank() } ?: preset.name) },
        onBackToList = onBackToList,
        backContentDescription = "Back to model presets",
        modifier = modifier,
        actions = {
            TextButton(onClick = { onEdit(preset) }) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Edit")
            }
            TextButton(
                onClick = { onDelete(preset) },
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
            if (preset.isIncomplete()) {
                StatusBadge(
                    text = "Incomplete",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            if (preset.description.isNotBlank()) {
                DetailRow(label = "Description", value = preset.description)
            }

            DetailRow(label = "Model", value = preset.modelLabel(modelsById))
            DetailRow(label = "Settings profile", value = preset.settingsLabel(settingsById))

            Text(
                text = "Updated ${formatRelativeTime(preset.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
