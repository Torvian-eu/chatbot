package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.app.compose.permissions.RequiresAnyPermission
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMModel

/**
 * Master panel for the Settings Config tab.
 * Contains model selection dropdown and settings list.
 */
@Composable
fun SettingsListPanel(
    models: List<LLMModel>,
    selectedModel: LLMModel?,
    settingsList: List<ModelSettingsDetails>,
    selectedSettings: ModelSettingsDetails?,
    onModelSelected: (LLMModel?) -> Unit,
    onSettingsSelected: (ModelSettingsDetails) -> Unit,
    onAddNewSettings: () -> Unit,
    authState: AuthState.Authenticated,
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
                    RequiresAnyPermission(
                        authState = authState,
                        permissions = listOf(CommonPermissions.CREATE_LLM_MODEL_SETTINGS, CommonPermissions.MANAGE_LLM_MODEL_SETTINGS)
                    ) {
                        FloatingActionButton(
                            onClick = onAddNewSettings,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Add, "Add new settings profile")
                        }
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
                    items(settingsList) { settingsDetails ->
                        SettingsListItem(
                            settingsDetails = settingsDetails,
                            isSelected = selectedSettings?.settings?.id == settingsDetails.settings.id,
                            onClick = { onSettingsSelected(settingsDetails) }
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
    settingsDetails: ModelSettingsDetails,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val settings = settingsDetails.settings

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

            // Public/Private badge (matches ProviderListItem/ModelsListPanel style)
            if (settingsDetails.isPublic()) {
                AssistChip(
                    onClick = {},
                    label = { Text("Public", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
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
                            modifier = Modifier.size(14.dp)
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

            Spacer(modifier = Modifier.width(8.dp))

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
