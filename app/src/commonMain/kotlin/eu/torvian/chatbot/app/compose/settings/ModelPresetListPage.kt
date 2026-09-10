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
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Full-width page for browsing the user's model presets.
 *
 * The page owns the shared shell, header copy and add action while [ModelPresetListItem] renders
 * individual rows. Rows arrive already in name-ascending order from the repository (U-24), so the
 * page deliberately does not re-sort.
 *
 * @param presets Presets to render in the list (name-ascending).
 * @param modelsById Model lookup used by the rows to resolve model references.
 * @param settingsById Settings lookup used by the rows to resolve settings references.
 * @param onPresetSelected Callback invoked when the user opens a preset detail page.
 * @param onAddNewPreset Callback invoked when the user starts the add-preset flow.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ModelPresetListPage(
    presets: List<ModelPresetDto>,
    modelsById: Map<Long, LLMModel>,
    settingsById: Map<Long, ModelSettings>,
    onPresetSelected: (ModelPresetDto) -> Unit,
    onAddNewPreset: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsListPageTemplate(
        title = "Model Presets",
        subtitle = if (presets.isEmpty()) {
            "No model presets yet. Use the add action to create your first preset."
        } else {
            "${presets.size} preset(s) • select a preset to view or edit its model and settings profile."
        },
        modifier = modifier,
        actions = {
            FilledTonalButton(onClick = onAddNewPreset) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add preset", maxLines = 1, softWrap = false)
            }
        }
    ) {
        if (presets.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No model presets configured yet.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Create one to bundle a model with a settings profile and attach it to agent roles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { preset ->
                    ModelPresetListItem(
                        preset = preset,
                        modelsById = modelsById,
                        settingsById = settingsById,
                        onClick = { onPresetSelected(preset) }
                    )
                }
            }
        }
    }
}
