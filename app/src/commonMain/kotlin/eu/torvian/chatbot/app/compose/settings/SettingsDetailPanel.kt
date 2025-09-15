package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.ChatModelSettings
import eu.torvian.chatbot.common.models.EmbeddingModelSettings
import eu.torvian.chatbot.common.models.ModelSettings

/**
 * Detail panel for the Settings Config tab.
 * Shows details of the currently selected settings profile.
 */
@Composable
fun SettingsDetailPanel(
    settings: ModelSettings?,
    onEdit: (ModelSettings) -> Unit,
    onDelete: (ModelSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        if (settings == null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text("Select a settings profile to view details.")
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header with actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(settings.name, style = MaterialTheme.typography.headlineSmall)
                    Row {
                        IconButton(onClick = { onEdit(settings) }) {
                            Icon(Icons.Default.Edit, "Edit Settings")
                        }
                        IconButton(onClick = { onDelete(settings) }) {
                            Icon(Icons.Default.Delete, "Delete Settings", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                
                // Details Section
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
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
