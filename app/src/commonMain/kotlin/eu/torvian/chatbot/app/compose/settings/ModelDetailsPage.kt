package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Full-width page for showing model details with the shared settings shell.
 *
 * The reusable model content lives in [ModelDetailsContent], while this page
 * supplies the model-specific title, back affordance, and header actions.
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

    SettingsDetailPage(
        categoryName = "Models",
        itemName = modelTitle,
        onBackToList = onBackToList,
        backContentDescription = "Back to Models",
        modifier = modifier,
        actions = {
            if (modelDetails.isPublic()) {
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
    ) {
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

