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
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Full-width page for showing provider details with the shared settings shell.
 *
 * The reusable provider content lives in [ProviderDetailsContent], while this page
 * supplies the provider-specific title, back affordance, and header actions.
 *
 * @param providerDetails Provider to display.
 * @param onBackToList Callback invoked when the user returns to the provider list.
 * @param onEditProvider Callback invoked when the user starts editing the provider.
 * @param onDeleteProvider Callback invoked when the user starts deleting the provider.
 * @param onListModels Callback invoked when the user requests discovered models.
 * @param onMakePublic Callback invoked when the user makes the provider public.
 * @param onMakePrivate Callback invoked when the user makes the provider private.
 * @param onManageAccess Callback invoked when the user opens the manage-access dialog.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ProviderDetailsPage(
    providerDetails: LLMProviderDetails,
    onBackToList: () -> Unit,
    onEditProvider: (LLMProvider) -> Unit,
    onDeleteProvider: (LLMProvider) -> Unit,
    onListModels: (LLMProviderDetails) -> Unit,
    onMakePublic: (LLMProviderDetails) -> Unit,
    onMakePrivate: (LLMProviderDetails) -> Unit,
    onManageAccess: (LLMProviderDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsDetailPage(
        categoryName = "Providers",
        itemName = providerDetails.provider.name,
        onBackToList = onBackToList,
        backContentDescription = "Back to Providers",
        modifier = modifier,
        actions = {
            if (providerDetails.isPublic()) {
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

            IconButton(onClick = { onEditProvider(providerDetails.provider) }) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit Provider",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = { onDeleteProvider(providerDetails.provider) }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Provider",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    ) {
        ProviderDetailsContent(
            providerDetails = providerDetails,
            onListModels = onListModels,
            onMakePublic = onMakePublic,
            onMakePrivate = onMakePrivate,
            onManageAccess = onManageAccess,
            modifier = Modifier.fillMaxSize()
        )
    }
}





