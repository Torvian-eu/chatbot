package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.response_duration_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_created_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_model_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_settings_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_updated_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_usage_section_title
import eu.torvian.chatbot.app.generated.resources.usage_dialog_usage_unavailable
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * Verifies how the message details dialog renders the measured response duration: formatted like a tool-call
 * duration when it is present, and omitted entirely when the message carries none.
 */
@OptIn(ExperimentalTestApi::class)
class MessageUsageDialogResponseDurationTest {

    private val baseInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val durationLabel: String = runBlocking { getString(Res.string.response_duration_label) }

    /**
     * Builds an assistant message fixture carrying the given duration and usage.
     *
     * @param responseDurationMs Duration of the provider call, or `null` when nothing was measured.
     * @param usageStats Usage of the message, or `null` when none was reported.
     * @return Assistant message for the shared session.
     */
    private fun assistantMessage(
        responseDurationMs: Long?,
        usageStats: UsageStats? = UsageStats(inputTokens = 111, outputTokens = 222, totalTokens = 333)
    ): ChatMessage.AssistantMessage =
        ChatMessage.AssistantMessage(
            id = 2L,
            sessionId = 1L,
            content = "Answer",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList(),
            modelId = 5L,
            settingsId = 6L,
            usageStats = usageStats,
            responseDurationMs = responseDurationMs
        )

    /**
     * Renders the dialog for the given message.
     *
     * @param message Message whose details are shown.
     */
    private fun ComposeUiTest.renderDialog(message: ChatMessage.AssistantMessage) {
        setContent {
            MessageUsageDialog(
                message = message,
                modelDisplayName = "Test Model",
                settingsDisplayName = "Default profile",
                onDismiss = {}
            )
        }
    }

    @Test
    fun `a measured duration is shown with the compact duration format`() = runComposeUiTest {
        renderDialog(assistantMessage(responseDurationMs = 4_300L))

        onNodeWithText("$durationLabel:").assertIsDisplayed()
        onNodeWithText(formatToolCallDuration(4_300L)).assertIsDisplayed()
        // The duration row joins the other provider facts without displacing any of them.
        onNodeWithText("${runBlocking { getString(Res.string.usage_dialog_model_label) }}:").assertIsDisplayed()
        onNodeWithText("${runBlocking { getString(Res.string.usage_dialog_settings_label) }}:").assertIsDisplayed()
        onNodeWithText("${runBlocking { getString(Res.string.usage_dialog_created_label) }}:").assertIsDisplayed()
        onNodeWithText("${runBlocking { getString(Res.string.usage_dialog_updated_label) }}:").assertIsDisplayed()
        onNodeWithText(runBlocking { getString(Res.string.usage_dialog_usage_section_title) }).assertIsDisplayed()
    }

    @Test
    fun `a message that was never measured shows no duration row`() = runComposeUiTest {
        renderDialog(assistantMessage(responseDurationMs = null))

        onNodeWithText("$durationLabel:").assertDoesNotExist()
        // The rest of the dialog is unaffected by the absent duration.
        onNodeWithText("${runBlocking { getString(Res.string.usage_dialog_model_label) }}:").assertIsDisplayed()
        onNodeWithText("${runBlocking { getString(Res.string.usage_dialog_settings_label) }}:").assertIsDisplayed()
        onNodeWithText("${runBlocking { getString(Res.string.usage_dialog_created_label) }}:").assertIsDisplayed()
        onNodeWithText("${runBlocking { getString(Res.string.usage_dialog_updated_label) }}:").assertIsDisplayed()
        onNodeWithText(runBlocking { getString(Res.string.usage_dialog_usage_section_title) }).assertIsDisplayed()
    }

    @Test
    fun `a zero duration is shown as a measurement rather than omitted`() = runComposeUiTest {
        renderDialog(assistantMessage(responseDurationMs = 0L))

        onNodeWithText("$durationLabel:").assertIsDisplayed()
        onNodeWithText(formatToolCallDuration(0L)).assertIsDisplayed()
    }

    @Test
    fun `a duration is shown next to the explicit unavailable usage statement`() = runComposeUiTest {
        renderDialog(assistantMessage(responseDurationMs = 900L, usageStats = null))

        onNodeWithText("$durationLabel:").assertIsDisplayed()
        onNodeWithText(formatToolCallDuration(900L)).assertIsDisplayed()
        onNodeWithText(runBlocking { getString(Res.string.usage_dialog_usage_unavailable) }).assertIsDisplayed()
    }
}
