package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails

/**
 * Body content for the models list page.
 *
 * The shared list-page shell now owns the page title and add action, so this
 * composable focuses on the model rows and empty-state behavior only.
 *
 * @param models Models to render in the list.
 * @param selectedModel Currently focused model, used only for row highlighting.
 * @param onModelSelected Callback invoked when the user opens a model detail page.
 * @param modifier Modifier applied to the body container.
 */
@Composable
fun ModelsListPanel(
    models: List<LLMModelDetails>,
    selectedModel: LLMModelDetails?,
    onModelSelected: (LLMModelDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (models.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No models configured yet.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Use the add action in the header to create your first model.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEach { model ->
                    ModelListItem(
                        modelDetails = model,
                        isSelected = selectedModel?.model?.id == model.model.id,
                        onClick = { onModelSelected(model) }
                    )
                }
            }
        }
    }
}

/**
 * Compact card used for a single model row.
 *
 * @param modelDetails Model shown in the row.
 * @param isSelected Whether the row is visually focused.
 * @param onClick Callback invoked when the row is activated.
 * @param modifier Modifier applied to the row card.
 */
@Composable
private fun ModelListItem(
    modelDetails: LLMModelDetails,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val model = modelDetails.model
    val shape = MaterialTheme.shapes.large

    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = shape,
        color = backgroundColor,
        shadowElevation = if (isSelected) 4.dp else 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = model.displayName?.takeIf { it.isNotBlank() } ?: model.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor
                    )

                    if (model.displayName?.isNotBlank() == true && model.displayName != model.name) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.72f)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (modelDetails.isPublic()) "Public" else "Private",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (modelDetails.isPublic()) Icons.Default.Public else Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 2.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (modelDetails.isPublic()) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            labelColor = if (modelDetails.isPublic()) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            leadingIconContentColor = if (modelDetails.isPublic()) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        ),
                        border = null
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = model.type.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Surface(
                        color = if (model.active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = if (model.active) "Active" else "Inactive",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (model.active) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
