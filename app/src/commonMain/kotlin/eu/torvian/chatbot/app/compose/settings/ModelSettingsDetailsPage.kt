package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
 * The page keeps the current model context visible alongside the profile name,
 * then renders the reusable settings details body and preserves the existing
 * edit, delete, and access-management flows.
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
    Card(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = onBackToList) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings profiles list")
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = selectedModel?.let { model ->
                            "${model.displayName?.takeIf { it.isNotBlank() } ?: model.name} / ${settingsDetails?.settings?.name ?: "Settings Profile"}"
                        } ?: (settingsDetails?.settings?.name ?: "Settings Profile"),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = selectedModel?.let { model ->
                            "Model context: ${model.displayName?.takeIf { it.isNotBlank() } ?: model.name}"
                        } ?: "Model context unavailable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            ModelSettingsDetailsBody(
                settingsDetails = settingsDetails,
                onEdit = onEdit,
                onDelete = onDelete,
                onMakePublic = onMakePublic,
                onMakePrivate = onMakePrivate,
                onManageAccess = onManageAccess,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}




