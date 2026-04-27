package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Reusable model detail body shared by the page-based view and legacy compatibility
 * entry points.
 *
 * The content keeps the read-only model sections and the current access-control
 * actions in one place so the page chrome can stay separate from the scrollable body.
 *
 * @param modelDetails Model whose detail body should be rendered.
 * @param onMakePublic Callback invoked when the user makes the model public.
 * @param onMakePrivate Callback invoked when the user makes the model private.
 * @param onManageAccess Callback invoked when the user opens the manage-access dialog.
 * @param providers Providers used to resolve a friendly provider name when available.
 * @param modifier Modifier applied to the scrollable body container.
 */
@Composable
fun ModelDetailsContent(
    modelDetails: LLMModelDetails,
    onMakePublic: (LLMModelDetails) -> Unit,
    onMakePrivate: (LLMModelDetails) -> Unit,
    onManageAccess: (LLMModelDetails) -> Unit,
    providers: List<LLMProvider>? = null,
    modifier: Modifier = Modifier
) {
    val model = modelDetails.model

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
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
                    text = modelDetails.getOwner() ?: "Unknown",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Access control remains a body section because it is part of the model's
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
                    if (modelDetails.isPublic()) {
                        Button(
                            onClick = { onMakePrivate(modelDetails) },
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
                            onClick = { onMakePublic(modelDetails) },
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
                        onClick = { onManageAccess(modelDetails) },
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Manage Access")
                    }
                }

                if (modelDetails.accessDetails.accessList.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "Shared with ${modelDetails.accessDetails.accessList.size} group(s)",
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
                    text = "Model Information",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ModelDetailsSection(model = model, providers = providers)
            }
        }
    }
}

/**
 * Structured body section for the read-only model attributes.
 *
 * @param model Model to summarize.
 * @param providers Providers used to render a readable provider name when available.
 * @param modifier Modifier applied to the container.
 */
@Composable
private fun ModelDetailsSection(
    model: LLMModel,
    providers: List<LLMProvider>? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DetailRow(
            label = "Display Name",
            value = model.displayName?.takeIf { it.isNotBlank() } ?: "Not set"
        )

        DetailRow(
            label = "Model Name",
            value = model.name
        )

        DetailRow(
            label = "Type",
            value = model.type.name
        )

        val providerName = providers?.firstOrNull { it.id == model.providerId }?.name
            ?: model.providerId.toString()

        DetailRow(
            label = "Provider",
            value = providerName
        )

        DetailRow(
            label = "Status",
            value = if (model.active) "Active" else "Inactive"
        )

        DetailRow(
            label = "Model ID",
            value = model.id.toString()
        )

        model.capabilities?.let { capabilities ->
            if (capabilities.isNotEmpty()) {
                DetailRow(
                    label = "Capabilities",
                    value = capabilities.toString()
                )
            }
        }
    }
}


