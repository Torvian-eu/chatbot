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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.thread_stats_assistant_messages_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_assistant_success_rate_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_average_response_duration_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_average_tool_call_duration_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_close_button
import eu.torvian.chatbot.app.generated.resources.thread_stats_empty
import eu.torvian.chatbot.app.generated.resources.thread_stats_input_cache_hit_rate_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_loading
import eu.torvian.chatbot.app.generated.resources.thread_stats_not_available
import eu.torvian.chatbot.app.generated.resources.thread_stats_outliers_none
import eu.torvian.chatbot.app.generated.resources.thread_stats_output_tokens_per_second_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_reasoning_items_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_response_duration_outliers_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_response_duration_stddev_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_section_messages
import eu.torvian.chatbot.app.generated.resources.thread_stats_section_rates
import eu.torvian.chatbot.app.generated.resources.thread_stats_section_timings
import eu.torvian.chatbot.app.generated.resources.thread_stats_section_tokens
import eu.torvian.chatbot.app.generated.resources.thread_stats_title
import eu.torvian.chatbot.app.generated.resources.thread_stats_tool_call_duration_outliers_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_tool_call_duration_stddev_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_tool_call_success_rate_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_tool_calls_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_total_messages_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_total_response_duration_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_total_tool_call_duration_label
import eu.torvian.chatbot.app.generated.resources.thread_stats_user_messages_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_cached_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_input_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_output_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_reasoning_tokens_label
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.round

/**
 * Dialog showing the server-computed statistics of the thread the chat screen currently displays.
 *
 * The dialog owns no state: it renders the load state it is given, so opening it again always shows the
 * statistics of the conversation as it is at that moment. Values the server could not measure are shown
 * as an explicit "not available" label rather than as a zero, and percentage-like values arrive as
 * fractions and are rendered here with one decimal place.
 *
 * @param stats Load state of the statistics of the displayed thread.
 * @param onReload Invoked when the user retries after a failure.
 * @param onDismiss Invoked when the dialog should close.
 */
@Composable
fun ThreadStatsDialog(
    stats: StateFlow<DataState<RepositoryError, ThreadStats>>,
    onReload: () -> Unit,
    onDismiss: () -> Unit
) {
    val statsState = stats.collectAsState().value

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.thread_stats_title)) },
        text = {
            when (statsState) {
                is DataState.Success -> ThreadStatsContent(stats = statsState.data)
                is DataState.Error -> ErrorStateDisplay(
                    error = statsState.error,
                    onRetry = onReload,
                    title = stringResource(Res.string.thread_stats_title)
                )

                DataState.Idle, DataState.Loading -> LoadingStateDisplay(
                    message = stringResource(Res.string.thread_stats_loading)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.thread_stats_close_button))
            }
        },
        modifier = Modifier.widthIn(min = 400.dp, max = 600.dp)
    )
}

/**
 * Renders the statistics of a non-empty thread, grouped into counts, tokens, timings and rates.
 *
 * @param stats Statistics to display.
 */
@Composable
private fun ThreadStatsContent(stats: ThreadStats) {
    if (stats.totalMessages == 0) {
        Text(
            text = stringResource(Res.string.thread_stats_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val notAvailable = stringResource(Res.string.thread_stats_not_available)
    val noOutliers = stringResource(Res.string.thread_stats_outliers_none)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThreadStatsSection(stringResource(Res.string.thread_stats_section_messages))
        ThreadStatsRow(stringResource(Res.string.thread_stats_total_messages_label), stats.totalMessages.toString())
        ThreadStatsRow(stringResource(Res.string.thread_stats_user_messages_label), stats.userMessages.toString())
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_assistant_messages_label),
            stats.assistantMessages.toString()
        )
        ThreadStatsRow(stringResource(Res.string.thread_stats_tool_calls_label), stats.toolCalls.toString())
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_reasoning_items_label),
            stats.reasoningItems.toString()
        )

        HorizontalDivider()

        ThreadStatsSection(stringResource(Res.string.thread_stats_section_tokens))
        ThreadStatsRow(
            stringResource(Res.string.usage_dialog_input_tokens_label),
            formatTokenTotal(stats.inputTokens, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.usage_dialog_output_tokens_label),
            formatTokenTotal(stats.outputTokens, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.usage_dialog_reasoning_tokens_label),
            formatTokenTotal(stats.reasoningTokens, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.usage_dialog_cached_tokens_label),
            formatTokenTotal(stats.cachedInputTokens, notAvailable)
        )

        HorizontalDivider()

        ThreadStatsSection(stringResource(Res.string.thread_stats_section_timings))
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_total_response_duration_label),
            formatDuration(stats.responseDuration.totalMs, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_average_response_duration_label),
            formatDurationOrNull(stats.responseDuration.averageMs, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_response_duration_stddev_label),
            formatDurationOrNull(stats.responseDuration.standardDeviationMs, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_response_duration_outliers_label),
            formatOutliers(stats.responseDuration.outliers, noOutliers)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_total_tool_call_duration_label),
            formatDuration(stats.toolCallDuration.totalMs, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_average_tool_call_duration_label),
            formatDurationOrNull(stats.toolCallDuration.averageMs, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_tool_call_duration_stddev_label),
            formatDurationOrNull(stats.toolCallDuration.standardDeviationMs, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_tool_call_duration_outliers_label),
            formatOutliers(stats.toolCallDuration.outliers, noOutliers)
        )

        HorizontalDivider()

        ThreadStatsSection(stringResource(Res.string.thread_stats_section_rates))
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_tool_call_success_rate_label),
            formatPercentage(stats.toolCallSuccessRate, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_assistant_success_rate_label),
            formatPercentage(stats.assistantMessageSuccessRate, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_output_tokens_per_second_label),
            formatDecimal(stats.averageOutputTokensPerSecond, notAvailable)
        )
        ThreadStatsRow(
            stringResource(Res.string.thread_stats_input_cache_hit_rate_label),
            formatPercentage(stats.inputCacheHitRate, notAvailable)
        )
    }
}

/**
 * Renders one label/value line of the thread stats dialog.
 *
 * @param label Localized label of the displayed statistic.
 * @param value Already-formatted value of the displayed statistic.
 */
@Composable
private fun ThreadStatsRow(label: String, value: String) {
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
 * Renders the heading of one statistic group.
 *
 * @param title Localized heading of the group.
 */
@Composable
private fun ThreadStatsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * Formats a token total reported by the branch.
 *
 * @param tokens Sum of the reported counter, or `null` when no message reported it.
 * @param notAvailable Label used for an unreported total.
 * @return The token count; a sum of reported zeros is rendered as `0`.
 */
private fun formatTokenTotal(tokens: Long?, notAvailable: String): String =
    tokens?.toString() ?: notAvailable

/**
 * Formats a total duration in milliseconds.
 *
 * @param durationMs Sum of the measured durations, or `null` when nothing was measured.
 * @param notAvailable Label used for a `null` total.
 * @return The compact duration text; a measured zero is rendered as `0ms`.
 */
private fun formatDuration(durationMs: Long?, notAvailable: String): String =
    durationMs?.let { formatToolCallDuration(it) } ?: notAvailable

/**
 * Formats a derived duration, which is absent when nothing was measured.
 *
 * @param durationMs Derived duration, or `null` when nothing was measured.
 * @param notAvailable Label used for a `null` duration.
 * @return The compact duration text.
 */
private fun formatDurationOrNull(durationMs: Double?, notAvailable: String): String =
    durationMs?.takeIf { it.isFinite() }?.let { formatToolCallDuration(round(it).toLong()) } ?: notAvailable

/**
 * Formats a fraction as a percentage with one decimal place.
 *
 * @param fraction Fraction in `[0,1]`, or `null` when the underlying set is empty.
 * @param notAvailable Label used for a `null` fraction.
 * @return The percentage text, e.g. `98.7%`.
 */
private fun formatPercentage(fraction: Double?, notAvailable: String): String =
    fraction?.takeIf { it.isFinite() }?.let { "${formatOneDecimal(it * 100.0)}%" } ?: notAvailable

/**
 * Formats an unlabelled decimal value with one decimal place.
 *
 * @param value Value to format, or `null` when it could not be derived.
 * @param notAvailable Label used for a `null` value.
 * @return The decimal text.
 */
private fun formatDecimal(value: Double?, notAvailable: String): String =
    value?.takeIf { it.isFinite() }?.let { formatOneDecimal(it) } ?: notAvailable

/**
 * Formats the identifiers of the slowest items.
 *
 * @param ids Outlier identifiers, longest first.
 * @param none Label used when there is no outlier.
 * @return The identifiers prefixed with `#`, or the empty label.
 */
private fun formatOutliers(ids: List<Long>, none: String): String =
    if (ids.isEmpty()) none else ids.joinToString(", ") { "#$it" }

/**
 * Rounds [value] to one decimal place.
 *
 * Ties land on the even tenth, because the rounding is delegated to [round], which breaks ties towards
 * the even integer.
 *
 * @param value Value to format.
 * @return The value with one decimal digit, always rendered.
 */
private fun formatOneDecimal(value: Double): String {
    val scaled = round(value * 10.0).toLong()
    return "${scaled / 10}.${abs(scaled % 10)}"
}
