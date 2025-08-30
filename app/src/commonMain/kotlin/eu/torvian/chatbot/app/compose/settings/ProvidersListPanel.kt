package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.EmptyStateDisplay
import eu.torvian.chatbot.common.models.LLMProvider

/**
 * Master panel showing the list of providers with selection support.
 */
@Composable
fun ProvidersListPanel(
    providers: List<LLMProvider>,
    selectedProvider: LLMProvider?,
    onProviderSelected: (LLMProvider) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Providers (${providers.size})",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (providers.isEmpty()) {
            EmptyStateDisplay(
                message = "No providers configured yet.\nClick the + button to add your first provider.",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(providers) { provider ->
                    ProviderListItem(
                        provider = provider,
                        isSelected = selectedProvider?.id == provider.id,
                        onClick = { onProviderSelected(provider) }
                    )
                }
            }
        }
    }
}
