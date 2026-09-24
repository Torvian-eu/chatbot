package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import kotlin.time.Instant
import org.junit.jupiter.api.Test

/**
 * Checks that both tool-call surfaces present durations consistently and omit unavailable durations.
 */
@OptIn(ExperimentalTestApi::class)
class ToolCallDurationPresentationTest {

    /** Verifies the badge displays the compact formatted value inside its existing parentheses. */
    @Test
    fun badge_showsFormattedDuration() = runComposeUiTest {
        setContent {
            ToolCallBadge(
                toolCall = toolCallWithDuration(1_200L),
                onClick = {}
            )
        }

        onNodeWithText("(1.2s)").assertIsDisplayed()
    }

    /** Verifies a missing badge duration does not render the parenthesized duration label. */
    @Test
    fun badge_omitsMissingDuration() = runComposeUiTest {
        setContent {
            ToolCallBadge(
                toolCall = toolCallWithDuration(null),
                onClick = {}
            )
        }

        onNodeWithText("sample-tool").assertIsDisplayed()
        onNodeWithText("(", substring = true).assertDoesNotExist()
    }

    /** Verifies the details dialog uses the same formatted value and preserves its prefix. */
    @Test
    fun detailsDialog_showsFormattedDuration() = runComposeUiTest {
        setContent {
            ToolCallDetailsDialog(
                toolCall = toolCallWithDuration(1_200L),
                onDismiss = {}
            )
        }

        onNodeWithText("Executed in 1.2s").assertIsDisplayed()
    }

    /** Verifies a missing dialog duration does not render its execution-time label. */
    @Test
    fun detailsDialog_omitsMissingDuration() = runComposeUiTest {
        setContent {
            ToolCallDetailsDialog(
                toolCall = toolCallWithDuration(null),
                onDismiss = {}
            )
        }

        onNodeWithText("Executed in", substring = true).assertDoesNotExist()
    }
}

/**
 * Creates a minimal successful tool call for duration presentation tests.
 * @param durationMs Optional execution duration to display.
 * @return A tool call with stable content and the requested duration.
 */
private fun toolCallWithDuration(durationMs: Long?): ToolCall = ToolCall(
    id = 1L,
    messageId = 1L,
    toolDefinitionId = null,
    toolName = "sample-tool",
    status = ToolCallStatus.SUCCESS,
    executedAt = Instant.fromEpochSeconds(0L),
    durationMs = durationMs
)
