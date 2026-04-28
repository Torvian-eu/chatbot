package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails

/**
 * Reusable provider detail body used by both the page-based view and the legacy
 * compatibility panel wrapper.
 *
 * The content includes the read-only provider sections and the current provider
 * actions for access control and model discovery.
 *
 * @param providerDetails Provider whose detail body should be rendered.
 * @param onListModels Callback invoked when the user requests discovered models.
 * @param onMakePublic Callback invoked when the user makes the provider public.
 * @param onMakePrivate Callback invoked when the user makes the provider private.
 * @param onManageAccess Callback invoked when the user opens the manage-access dialog.
 * @param modifier Modifier applied to the scrollable body container.
 */
@Composable
fun ProviderDetailsContent(
    providerDetails: LLMProviderDetails,
    onListModels: (LLMProviderDetails) -> Unit,
    onMakePublic: (LLMProviderDetails) -> Unit,
    onMakePrivate: (LLMProviderDetails) -> Unit,
    onManageAccess: (LLMProviderDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    val provider = providerDetails.provider

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Owner information is a stable read-only summary, so it stays in the body.
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
                    text = providerDetails.getOwner() ?: "Unknown",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Access control remains a body section because it is part of the provider's
        // domain state rather than page chrome.
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
                    if (providerDetails.isPublic()) {
                        Button(
                            onClick = { onMakePrivate(providerDetails) },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text("Make Private")
                        }
                    } else {
                        Button(
                            onClick = { onMakePublic(providerDetails) },
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text("Make Public")
                        }
                    }

                    OutlinedButton(
                        onClick = { onManageAccess(providerDetails) },
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Manage Access")
                    }
                }

                if (providerDetails.accessDetails.accessList.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "Shared with ${providerDetails.accessDetails.accessList.size} group(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Model Discovery",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = { onListModels(providerDetails) },
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Text("List Models")
                }
            }
        }

        ProviderDetailsSection(provider = provider)
    }
}



