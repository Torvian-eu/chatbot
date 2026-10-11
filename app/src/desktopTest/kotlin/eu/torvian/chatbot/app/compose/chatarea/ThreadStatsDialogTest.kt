package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.thread_stats_not_available
import eu.torvian.chatbot.app.generated.resources.thread_stats_section_cost
import eu.torvian.chatbot.app.generated.resources.thread_stats_title
import eu.torvian.chatbot.app.generated.resources.thread_stats_total_cost_label
import eu.torvian.chatbot.common.models.api.core.DurationStats
import eu.torvian.chatbot.common.models.api.core.ThreadStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.jupiter.api.Test

/**
 * Verifies what the thread statistics dialog shows for the summed charge of the displayed branch.
 *
 * Every other statistic of the fixture is reported, so the cost row is the only value the dialog can render as
 * the explicit "not available" label and a zero amount can only come from a charge the branch did report.
 */
@OptIn(ExperimentalTestApi::class)
class ThreadStatsDialogTest {

    private val title: String = runBlocking { getString(Res.string.thread_stats_title) }
    private val costSection: String = runBlocking { getString(Res.string.thread_stats_section_cost) }
    private val totalCostLabel: String = runBlocking { getString(Res.string.thread_stats_total_cost_label) }
    private val notAvailable: String = runBlocking { getString(Res.string.thread_stats_not_available) }

    /** Builds statistics of a one-message branch that reported everything except, optionally, its charge. */
    private fun threadStats(cost: Double?): ThreadStats = ThreadStats(
        totalMessages = 1,
        userMessages = 1,
        assistantMessages = 0,
        toolCalls = 0,
        reasoningItems = 0,
        inputTokens = 100L,
        outputTokens = 10L,
        reasoningTokens = 0L,
        cachedInputTokens = 0L,
        cost = cost,
        responseDuration = DurationStats(
            totalMs = 1_000L,
            averageMs = 1_000.0,
            standardDeviationMs = 0.0,
            outliers = emptyList()
        ),
        toolCallDuration = DurationStats(
            totalMs = 0L,
            averageMs = 0.0,
            standardDeviationMs = 0.0,
            outliers = emptyList()
        ),
        toolCallSuccessRate = 1.0,
        assistantMessageSuccessRate = 1.0,
        averageOutputTokensPerSecond = 10.0,
        inputCacheHitRate = 0.5
    )

    @Test
    fun `a reported total charge is shown as the formatted dollar amount`() = runComposeUiTest {
        val stats = threadStats(cost = 950.2466)

        setContent {
            ThreadStatsDialog(
                stats = MutableStateFlow(DataState.Success(stats)),
                onReload = {},
                onDismiss = {}
            )
        }

        onNodeWithText(title).assertIsDisplayed()
        onNodeWithText(costSection).assertIsDisplayed()
        onNodeWithText("$totalCostLabel:").assertIsDisplayed()
        // The expected text is spelled out here instead of coming from the formatter, so this test fails when the
        // dialog drops the value and when the rendering of it changes.
        onNodeWithText("$950.25").assertIsDisplayed()
    }

    @Test
    fun `a branch that reports no charge shows the not available label`() = runComposeUiTest {
        setContent {
            ThreadStatsDialog(
                stats = MutableStateFlow(DataState.Success(threadStats(cost = null))),
                onReload = {},
                onDismiss = {}
            )
        }

        onNodeWithText(costSection).assertIsDisplayed()
        onNodeWithText("$totalCostLabel:").assertIsDisplayed()
        onNodeWithText(notAvailable).assertIsDisplayed()
    }

    @Test
    fun `a total charge of zero is shown as a zero amount`() = runComposeUiTest {
        setContent {
            ThreadStatsDialog(
                stats = MutableStateFlow(DataState.Success(threadStats(cost = 0.0))),
                onReload = {},
                onDismiss = {}
            )
        }

        onNodeWithText("$totalCostLabel:").assertIsDisplayed()
        onNodeWithText("$0.00").assertIsDisplayed()
    }
}
