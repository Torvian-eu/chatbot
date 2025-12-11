package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.EmbeddingModelSettings
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Detail panel for the Settings Config tab.
 * Shows details of the currently selected settings profile.
 */
@Composable
fun ModelSettingsDetailPanel(
    settingsDetails: ModelSettingsDetails?,
    onEdit: (ModelSettings) -> Unit,
    onDelete: (ModelSettings) -> Unit,
    onMakePublic: (ModelSettingsDetails) -> Unit,
    onMakePrivate: (ModelSettingsDetails) -> Unit,
    onManageAccess: (ModelSettingsDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        if (settingsDetails == null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text("Select a settings profile to view details")
            }
        } else {
            val settings = settingsDetails.settings
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header with actions and badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = settings.name,
                            style = MaterialTheme.typography.headlineSmall
                        )

                        // Public/Private badge
                        if (settingsDetails.isPublic()) {
                            AssistChip(
                                onClick = {},
                                label = { Text("Public") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Public,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
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
                                label = { Text("Private") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
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
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { onEdit(settings) }) {
                            Icon(Icons.Default.Edit, "Edit settings")
                        }
                        IconButton(onClick = { onDelete(settings) }) {
                            Icon(Icons.Default.Delete, "Delete settings", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // Details Section
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Owner information card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Owner",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = settingsDetails.getOwner() ?: "Unknown",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Access control card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Access Control",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (settingsDetails.isPublic()) {
                                        OutlinedButton(
                                            onClick = { onMakePrivate(settingsDetails) },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        ) {
                                            Icon(
                                                Icons.Default.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Make Private")
                                        }
                                    } else {
                                        Button(
                                            onClick = { onMakePublic(settingsDetails) },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        ) {
                                            Icon(
                                                Icons.Default.Public,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Make Public")
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = { onManageAccess(settingsDetails) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Group,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Manage Access")
                                    }
                                }

                                // Show current access summary
                                if (settingsDetails.accessDetails.accessList.isNotEmpty()) {
                                    HorizontalDivider()
                                    Text(
                                        text = "Shared with ${settingsDetails.accessDetails.accessList.size} group(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }

                    item { DetailRow("Profile Name", settings.name) }
                    item { DetailRow("Profile ID", settings.id.toString()) }
                    item { DetailRow("Model Type", settings.modelType.name) }

                    // Type-specific details
                    when (settings) {
                        is ChatModelSettings -> ChatSettingsDetails(settings)
                        is EmbeddingModelSettings -> EmbeddingSettingsDetails(settings)
                        // Add cases for other settings types as they are implemented
                        else -> item { Text("Details for this settings type are not yet implemented.") }
                    }
                }
            }
        }
    }
}

/**
 * Helper composable for displaying a detail row.
 */
@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Chat-specific settings details.
 */
private fun LazyListScope.ChatSettingsDetails(settings: ChatModelSettings) {
    item { DetailRow("System Message", settings.systemMessage ?: "Not set") }
    item { DetailRow("Temperature", settings.temperature?.toString() ?: "Default") }
    item { DetailRow("Max Tokens", settings.maxTokens?.toString() ?: "Default") }
    item { DetailRow("Top P", settings.topP?.toString() ?: "Default") }
    item { DetailRow("Stream", settings.stream.toString()) }
    item { DetailRow("Stop Sequences", settings.stopSequences?.joinToString(", ") ?: "None") }
}

/**
 * Embedding-specific settings details.
 */
private fun LazyListScope.EmbeddingSettingsDetails(settings: EmbeddingModelSettings) {
    item { DetailRow("Dimensions", settings.dimensions?.toString() ?: "Default") }
    item { DetailRow("Encoding Format", settings.encodingFormat ?: "Default") }
}
