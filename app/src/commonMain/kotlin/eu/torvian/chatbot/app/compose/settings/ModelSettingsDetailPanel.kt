package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
 * Reusable body for the Model Settings details view.
 *
 * The body contains the profile-specific controls and information without any
 * outer container so it can be embedded in both the legacy split-view panel and
 * the new full-width details page.
 *
 * @param settingsDetails The currently opened settings profile, or null when the
 * selection is unavailable.
 * @param onEdit Callback used to start editing the current settings profile.
 * @param onDelete Callback used to start deleting the current settings profile.
 * @param onMakePublic Callback used to make the current settings profile public.
 * @param onMakePrivate Callback used to make the current settings profile private.
 * @param onManageAccess Callback used to open the manage-access dialog.
 * @param showHeader Whether the page-style header and divider should be rendered.
 * @param modifier Modifier applied to the body container.
 */
@Composable
fun ModelSettingsDetailsBody(
    settingsDetails: ModelSettingsDetails?,
    onEdit: (ModelSettings) -> Unit,
    onDelete: (ModelSettings) -> Unit,
    onMakePublic: (ModelSettingsDetails) -> Unit,
    onMakePrivate: (ModelSettingsDetails) -> Unit,
    onManageAccess: (ModelSettingsDetails) -> Unit,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (settingsDetails == null) {
        Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
            Text("Select a settings profile to view details")
        }
    } else {
        val settings = settingsDetails.settings
        Column(modifier = modifier.fillMaxSize().padding(16.dp)) {

            // Details section.
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Owner information card.

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


                // Access control card.

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

                        // Show the current access summary so the page remains self-contained after the migration.
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


                DetailRow("Profile Name", settings.name)
                DetailRow("Profile ID", settings.id.toString())
                DetailRow("Model Type", settings.modelType.name)

                // Type-specific details.
                when (settings) {
                    is ChatModelSettings -> ChatSettingsDetails(settings)
                    is EmbeddingModelSettings -> EmbeddingSettingsDetails(settings)
                    // Add cases for other settings types as they are implemented.
                    else -> Text("Details for this settings type are not yet implemented.")
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
@Composable
private fun ChatSettingsDetails(settings: ChatModelSettings) {
    DetailRow("System Message", settings.systemMessage ?: "Not set")
    DetailRow("Temperature", settings.temperature?.toString() ?: "Default")
    DetailRow("Max Tokens", settings.maxTokens?.toString() ?: "Default")
    DetailRow("Top P", settings.topP?.toString() ?: "Default")
    DetailRow("Stream", settings.stream.toString())
    DetailRow("Stop Sequences", settings.stopSequences?.joinToString(", ") ?: "None")
}

/**
 * Embedding-specific settings details.
 */
@Composable
private fun EmbeddingSettingsDetails(settings: EmbeddingModelSettings) {
    DetailRow("Dimensions", settings.dimensions?.toString() ?: "Default")
    DetailRow("Encoding Format", settings.encodingFormat ?: "Default")
}
