package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMModel

/**
 * Body content for the model settings list page.
 *
 * The shared list-page shell now owns the page title and add action, so this
 * composable focuses on the model selector, settings rows, and empty-state copy.
 *
 * @param models Available models loaded for the tab.
 * @param selectedModel The currently selected model context, if any.
 * @param settingsList Settings profiles that belong to the selected model.
 * @param selectedSettings The currently selected settings profile, used for row highlighting.
 * @param onModelSelected Callback used when the model context changes.
 * @param onSettingsSelected Callback used when a settings profile is opened.
 * @param modifier Modifier applied to the body container.
 */
@Composable
fun ModelSettingsListPanel(
    models: List<LLMModel>,
    selectedModel: LLMModel?,
    settingsList: List<ModelSettingsDetails>,
    selectedSettings: ModelSettingsDetails?,
    onModelSelected: (LLMModel?) -> Unit,
    onSettingsSelected: (ModelSettingsDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Model context",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ConfigDropdown(
                    label = "Select Model",
                    items = models,
                    selectedItem = selectedModel,
                    onItemSelected = onModelSelected,
                    itemText = { it.displayName ?: it.name }
                )
            }
        }

        if (selectedModel == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Select a model to view its settings profiles.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else if (settingsList.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No settings profiles found for this model.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Use the add action in the header to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                settingsList.forEach { settingsDetails ->
                    ModelSettingsListItem(
                        settingsDetails = settingsDetails,
                        isSelected = selectedSettings?.settings?.id == settingsDetails.settings.id,
                        onClick = { onSettingsSelected(settingsDetails) }
                    )
                }
            }
        }
    }
}

/**
 * Compact card used for a single settings profile row.
 *
 * @param settingsDetails Settings profile shown in the row.
 * @param isSelected Whether the row is visually focused.
 * @param onClick Callback invoked when the row is activated.
 */
@Composable
private fun ModelSettingsListItem(
    settingsDetails: ModelSettingsDetails,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val settings = settingsDetails.settings
    val shape = MaterialTheme.shapes.large

    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = shape,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shadowElevation = if (isSelected) 4.dp else 1.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = settings.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )

            if (settingsDetails.isPublic()) {
                AssistChip(
                    onClick = {},
                    label = { Text("Public", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ),
                    border = null
                )
            } else {
                AssistChip(
                    onClick = {},
                    label = { Text("Private", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    border = null
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = settings.modelType.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}
