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
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Full-width page for an individual Model Settings profile.
 *
 * The page now uses the shared settings shell for its top-level navigation and
 * leaves the existing reusable settings body to render the profile contents.
 *
 * @param selectedModel The model currently in scope, used for contextual labelling.
 * @param settingsDetails The opened settings profile, or null when the page is being restored.
 * @param onBackToList Callback used to return to the settings profiles list.
 * @param onEdit Callback used to start editing the current settings profile.
 * @param onDelete Callback used to start deleting the current settings profile.
 * @param onMakePublic Callback used to make the current settings profile public.
 * @param onMakePrivate Callback used to make the current settings profile private.
 * @param onManageAccess Callback used to open the manage-access dialog.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ModelSettingsDetailsPage(
    selectedModel: LLMModel?,
    settingsDetails: ModelSettingsDetails?,
    onBackToList: () -> Unit,
    onEdit: (ModelSettings) -> Unit,
    onDelete: (ModelSettings) -> Unit,
    onMakePublic: (ModelSettingsDetails) -> Unit,
    onMakePrivate: (ModelSettingsDetails) -> Unit,
    onManageAccess: (ModelSettingsDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    val modelContext = selectedModel?.let { model ->
        model.displayName?.takeIf { it.isNotBlank() } ?: model.name
    }

    SettingsDetailPage(
        categoryName = "Model Settings",
        itemName = settingsDetails?.settings?.name ?: "Settings Profile",
        supportingText = modelContext ?: "Model context unavailable.",
        onBackToList = onBackToList,
        backContentDescription = "Back to settings profiles list",
        modifier = modifier,
        actions = {
            if (settingsDetails != null) {
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

                IconButton(onClick = { onEdit(settingsDetails.settings) }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit settings",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = { onDelete(settingsDetails.settings) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete settings",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    ) {
        if (settingsDetails == null) {
            Text(
                text = "Loading settings profile...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            ModelSettingsDetailsBody(
                settingsDetails = settingsDetails,
                onEdit = onEdit,
                onDelete = onDelete,
                onMakePublic = onMakePublic,
                onMakePrivate = onMakePrivate,
                onManageAccess = onManageAccess,
                showHeader = false,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}




