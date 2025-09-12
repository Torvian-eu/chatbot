package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings

/**
 * Master panel for the Settings Config tab.
 * Contains model selection dropdown and settings list.
 */
@Composable
fun SettingsListPanel(
    models: List<LLMModel>,
    selectedModel: LLMModel?,
    settingsList: List<ModelSettings>,
    selectedSettings: ModelSettings?,
    onModelSelected: (LLMModel?) -> Unit,
    onSettingsSelected: (ModelSettings) -> Unit,
    onAddNewSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings Profiles", style = MaterialTheme.typography.headlineSmall)
                if (selectedModel != null) {
                    FloatingActionButton(
                        onClick = onAddNewSettings,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Add, "Add new settings profile")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Model Selector
            ConfigDropdown(
                label = "Select Model",
                items = models,
                selectedItem = selectedModel,
                onItemSelected = onModelSelected,
                itemText = { it.displayName ?: it.name }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Settings List
            if (selectedModel == null) {
                Text("Select a model to see its settings profiles.", textAlign = TextAlign.Center)
            } else if (settingsList.isEmpty()) {
                Text("No settings profiles found for this model.", textAlign = TextAlign.Center)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(settingsList) { settings ->
                        SettingsListItem(
                            settings = settings,
                            isSelected = selectedSettings?.id == settings.id,
                            onClick = { onSettingsSelected(settings) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual item in the settings list.
 */
@Composable
private fun SettingsListItem(
    settings: ModelSettings,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = settings.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = settings.modelType.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}
