package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails

/**
 * Body content for the providers list page.
 *
 * The shared list-page shell now owns the page title and add action, so this
 * composable focuses on the provider rows and empty-state presentation only.
 *
 * @param providers Providers to render in the list.
 * @param selectedProvider Currently focused provider, used only for row highlighting.
 * @param onProviderSelected Callback invoked when the user opens a provider detail page.
 * @param modifier Modifier applied to the body container.
 */
@Composable
fun ProvidersListPanel(
    providers: List<LLMProviderDetails>,
    selectedProvider: LLMProviderDetails?,
    onProviderSelected: (LLMProviderDetails) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (providers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No providers configured yet.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Use the add action in the header to create your first provider.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { provider ->
                    ProviderListItem(
                        providerDetails = provider,
                        isSelected = selectedProvider?.provider?.id == provider.provider.id,
                        onClick = { onProviderSelected(provider) }
                    )
                }
            }
        }
    }
}
