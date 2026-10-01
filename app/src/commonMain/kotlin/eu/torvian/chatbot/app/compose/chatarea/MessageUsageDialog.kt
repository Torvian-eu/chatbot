package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.usage_dialog_cache_write_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_cached_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_close_button
import eu.torvian.chatbot.app.generated.resources.usage_dialog_created_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_input_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_model_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_output_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_reasoning_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_settings_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_title
import eu.torvian.chatbot.app.generated.resources.usage_dialog_total_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_unknown_value
import eu.torvian.chatbot.app.generated.resources.usage_dialog_updated_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_usage_section_title
import eu.torvian.chatbot.app.generated.resources.usage_dialog_usage_unavailable
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import org.jetbrains.compose.resources.stringResource

/**
 * Dialog showing which model and settings profile produced one assistant message, when it was created and last
 * updated, and the token usage the provider reported for it.
 *
 * The usage block is omitted and replaced by an explicit statement when the message carries none, so "no usage"
 * is never presented as a set of zero counters. Cost information is deliberately not shown.
 *
 * @param message Finalized assistant message whose details are displayed.
 * @param modelDisplayName Name of the producing model, or `null` when it cannot be resolved.
 * @param settingsDisplayName Name of the producing settings profile, or `null` when it cannot be resolved.
 * @param onDismiss Invoked when the dialog should close.
 */
@Composable
fun MessageUsageDialog(
    message: ChatMessage.AssistantMessage,
    modelDisplayName: String?,
    settingsDisplayName: String?,
    onDismiss: () -> Unit
) {
    val unknownValue = stringResource(Res.string.usage_dialog_unknown_value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.usage_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MessageUsageDetailRow(
                    label = stringResource(Res.string.usage_dialog_model_label),
                    value = modelDisplayName ?: unknownValue
                )
                MessageUsageDetailRow(
                    label = stringResource(Res.string.usage_dialog_settings_label),
                    value = settingsDisplayName ?: unknownValue
                )
                MessageUsageDetailRow(
                    label = stringResource(Res.string.usage_dialog_created_label),
                    value = formatInstant(message.createdAt)
                )
                MessageUsageDetailRow(
                    label = stringResource(Res.string.usage_dialog_updated_label),
                    value = formatInstant(message.updatedAt)
                )

                HorizontalDivider()

                MessageUsageSection(usageStats = message.usageStats)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.usage_dialog_close_button))
            }
        },
        modifier = Modifier.widthIn(min = 400.dp, max = 600.dp)
    )
}

/**
 * Renders one label/value line of the message usage dialog.
 *
 * @param label Localized label of the displayed fact.
 * @param value Already-formatted value of the displayed fact.
 */
@Composable
private fun MessageUsageDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Renders the token-usage block of the message usage dialog.
 *
 * The three optional counters get a line of their own only when the provider reported them, so an omitted counter
 * is absent rather than shown as zero.
 *
 * @param usageStats Usage reported for the message, or `null` when there is none to show.
 */
@Composable
private fun MessageUsageSection(usageStats: UsageStats?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.usage_dialog_usage_section_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        if (usageStats == null) {
            Text(
                text = stringResource(Res.string.usage_dialog_usage_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        MessageUsageDetailRow(
            label = stringResource(Res.string.usage_dialog_input_tokens_label),
            value = usageStats.inputTokens.toString()
        )
        MessageUsageDetailRow(
            label = stringResource(Res.string.usage_dialog_output_tokens_label),
            value = usageStats.outputTokens.toString()
        )
        MessageUsageDetailRow(
            label = stringResource(Res.string.usage_dialog_total_tokens_label),
            value = usageStats.totalTokens.toString()
        )
        usageStats.reasoningTokens?.let { reasoningTokens ->
            MessageUsageDetailRow(
                label = stringResource(Res.string.usage_dialog_reasoning_tokens_label),
                value = reasoningTokens.toString()
            )
        }
        usageStats.cachedTokens?.let { cachedTokens ->
            MessageUsageDetailRow(
                label = stringResource(Res.string.usage_dialog_cached_tokens_label),
                value = cachedTokens.toString()
            )
        }
        usageStats.cacheWriteTokens?.let { cacheWriteTokens ->
            MessageUsageDetailRow(
                label = stringResource(Res.string.usage_dialog_cache_write_tokens_label),
                value = cacheWriteTokens.toString()
            )
        }
    }
}
