package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider

/**
 * Panel displaying details of a selected LLM model with edit and delete actions.
 * Implements the detail side of the master-detail layout for E4.S2-S4.
 */
@Composable
fun ModelDetailPanel(
    model: LLMModel?,
    onEditModel: (LLMModel) -> Unit,
    onDeleteModel: (LLMModel) -> Unit,
    providers: List<LLMProvider>? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        if (model == null) {
            // No model selected state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Select a model to view details",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header with actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Model Details",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { onEditModel(model) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit model",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(
                            onClick = { onDeleteModel(model) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete model",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Model information
                ModelDetailsContent(model = model, providers = providers)
            }
        }
    }
}

/**
 * Content component displaying the model information in a structured format.
 */
@Composable
private fun ModelDetailsContent(
    model: LLMModel,
    providers: List<LLMProvider>? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Display Name and Name
        DetailRow(
            label = "Display Name",
            value = model.displayName?.takeIf { it.isNotBlank() } ?: "Not set"
        )

        if (model.displayName?.isNotBlank() == true) {
            DetailRow(
                label = "Model Name",
                value = model.name
            )
        } else {
            DetailRow(
                label = "Model Name",
                value = model.name
            )
        }

        // Model Type
        DetailRow(
            label = "Type",
            value = model.type.name
        )

        // Provider: prefer friendly provider name when available
        val providerName = providers?.firstOrNull { it.id == model.providerId }?.name
            ?: model.providerId.toString()

        DetailRow(
            label = "Provider",
            value = providerName
        )

        // Active Status
        DetailRow(
            label = "Status",
            value = if (model.active) "Active" else "Inactive"
        )

        // Model ID (for debugging/admin purposes)
        DetailRow(
            label = "Model ID",
            value = model.id.toString()
        )
    }
}
