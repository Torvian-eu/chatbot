package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Full-width page for showing model details and page-level navigation.
 *
 * The reusable model content lives in [ModelDetailsContent], while this page owns
 * the breadcrumb-friendly navigation chrome and the model title/actions.
 *
 * @param modelDetails Model to display.
 * @param onBackToList Callback invoked when the user returns to the model list.
 * @param onEditModel Callback invoked when the user starts editing the model.
 * @param onDeleteModel Callback invoked when the user starts deleting the model.
 * @param onMakePublic Callback invoked when the user makes the model public.
 * @param onMakePrivate Callback invoked when the user makes the model private.
 * @param onManageAccess Callback invoked when the user opens the manage-access dialog.
 * @param providers Providers used to resolve a friendly provider name when available.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ModelDetailsPage(
    modelDetails: LLMModelDetails,
    onBackToList: () -> Unit,
    onEditModel: (LLMModel) -> Unit,
    onDeleteModel: (LLMModel) -> Unit,
    onMakePublic: (LLMModelDetails) -> Unit,
    onMakePrivate: (LLMModelDetails) -> Unit,
    onManageAccess: (LLMModelDetails) -> Unit,
    providers: List<LLMProvider>? = null,
    modifier: Modifier = Modifier
) {
    val model = modelDetails.model
    val modelTitle = model.displayName?.takeIf { it.isNotBlank() } ?: model.name

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBackToList) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back to Models")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = modelTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (modelDetails.isPublic()) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Public") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null
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
                                contentDescription = null
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

            Row {
                IconButton(onClick = { onEditModel(model) }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Model",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = { onDeleteModel(model) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Model",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        ModelDetailsContent(
            modelDetails = modelDetails,
            onMakePublic = onMakePublic,
            onMakePrivate = onMakePrivate,
            onManageAccess = onManageAccess,
            providers = providers,
            modifier = Modifier.fillMaxSize()
        )
    }
}

