package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Section displaying provider details in a structured format.
 */
@Composable
fun ProviderDetailsSection(
    provider: LLMProvider,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Provider Type
        DetailRow(
            label = "Type",
            value = provider.type.name
        )

        // Base URL
        DetailRow(
            label = "Base URL",
            value = provider.baseUrl
        )

        // Description
        if (provider.description.isNotBlank()) {
            DetailRow(
                label = "Description",
                value = provider.description
            )
        }

        // API Key Status
        DetailRow(
            label = "API Key Status",
            value = if (provider.apiKeyId != null) {
                "✓ Configured"
            } else {
                "Not configured"
            }
        )

        // Provider ID (for debugging/reference)
        DetailRow(
            label = "Provider ID",
            value = provider.id.toString()
        )
    }
}
