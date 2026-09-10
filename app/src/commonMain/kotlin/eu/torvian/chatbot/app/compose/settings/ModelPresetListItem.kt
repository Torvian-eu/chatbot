package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.StatusBadge
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Compact card used for a single model-preset row in the management list.
 *
 * The row shows the preset's display name (falling back to its machine name), its referenced model
 * and settings profile, and an "Incomplete" badge while either reference is null. A preset with a
 * null reference is a *normal, still-attachable* row (`ON DELETE SET NULL` produces exactly this
 * state), so it is marked rather than hidden.
 *
 * @param preset Preset shown in the row.
 * @param modelsById Model lookup used to resolve the preset's model reference into a label.
 * @param settingsById Settings lookup used to resolve the preset's settings reference into a label.
 * @param onClick Callback invoked when the row is activated.
 * @param modifier Modifier applied to the row card.
 */
@Composable
fun ModelPresetListItem(
    preset: ModelPresetDto,
    modelsById: Map<Long, LLMModel>,
    settingsById: Map<Long, ModelSettings>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp
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
                val displayName = preset.displayName?.takeIf { it.isNotBlank() }
                Text(
                    text = displayName ?: preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Only echoed when it differs from the title, so the row is not redundant.
                if (displayName != null) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Model: ${preset.modelLabel(modelsById)} • Settings: ${preset.settingsLabel(settingsById)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (preset.isIncomplete()) {
                    StatusBadge(
                        text = "Incomplete",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/**
 * Resolves the preset's model reference into a user-facing label.
 *
 * Shared by the list row and the detail page so both describe the reference identically.
 *
 * @receiver The preset whose [ModelPresetDto.modelId] is resolved.
 * @param modelsById Model lookup from the model stream.
 * @return The model's display name (falling back to its name), "No model" when the preset has no
 *         reference, or a "not found" note when the id is not resolvable by this client.
 */
internal fun ModelPresetDto.modelLabel(modelsById: Map<Long, LLMModel>): String {
    val modelId = modelId ?: return "No model"
    val model = modelsById[modelId] ?: return "Model #$modelId (not found)"
    return model.displayName?.takeIf { it.isNotBlank() } ?: model.name
}

/**
 * Resolves the preset's settings-profile reference into a user-facing label.
 *
 * Shared by the list row and the detail page so both describe the reference identically.
 *
 * @receiver The preset whose [ModelPresetDto.modelSettingsId] is resolved.
 * @param settingsById Settings lookup from the settings stream.
 * @return The profile's name, "No settings" when the preset has no reference, or a "not found" note
 *         when the id is not resolvable by this client.
 */
internal fun ModelPresetDto.settingsLabel(settingsById: Map<Long, ModelSettings>): String {
    val settingsId = modelSettingsId ?: return "No settings"
    val settings = settingsById[settingsId] ?: return "Settings #$settingsId (not found)"
    return "${settings.name} (${settings.modelType})"
}

/**
 * Whether the preset is missing at least one of its references.
 *
 * An incomplete preset is still valid and attachable — it simply cannot drive a turn until its
 * references are restored. The flag is derived from the references themselves, so it also covers the
 * state produced when a referenced model/settings row is deleted (`ON DELETE SET NULL`).
 *
 * @receiver The preset to classify.
 * @return True when the model or the settings reference is null.
 */
internal fun ModelPresetDto.isIncomplete(): Boolean = modelId == null || modelSettingsId == null
