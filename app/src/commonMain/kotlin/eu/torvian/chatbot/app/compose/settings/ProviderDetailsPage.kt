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
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Full-width page for showing provider details and page-level navigation.
 *
 * The reusable provider content lives in [ProviderDetailsContent], while this page
 * owns the breadcrumb-friendly navigation chrome and the provider title/actions.
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
                Text("Back to Providers")
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
                    text = providerDetails.provider.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (providerDetails.isPublic()) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Public") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                    modifier = Modifier.width(16.dp)
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
                                    modifier = Modifier.width(16.dp)
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
        }

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





