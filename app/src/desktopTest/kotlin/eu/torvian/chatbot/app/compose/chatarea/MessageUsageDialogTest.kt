package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.usage_dialog_cache_write_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_cached_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_input_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_model_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_output_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_reasoning_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_settings_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_title
import eu.torvian.chatbot.app.generated.resources.usage_dialog_total_tokens_label
import eu.torvian.chatbot.app.generated.resources.usage_dialog_unknown_value
import eu.torvian.chatbot.app.generated.resources.usage_dialog_usage_section_title
import eu.torvian.chatbot.app.generated.resources.usage_dialog_usage_unavailable
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Verifies what the message usage dialog shows for a message with reported usage, without usage, and with
 * unresolvable model/settings names.
 *
 * The counters are asserted through their localized labels and the exact reported values, so a zero-filled value
 * or a counter the provider never reported would fail these tests.
 */
@OptIn(ExperimentalTestApi::class)
class MessageUsageDialogTest {

    private val baseInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val unknownValue: String = runBlocking { getString(Res.string.usage_dialog_unknown_value) }
    private val modelLabel: String = runBlocking { getString(Res.string.usage_dialog_model_label) }
    private val settingsLabel: String = runBlocking { getString(Res.string.usage_dialog_settings_label) }
    private val title: String = runBlocking { getString(Res.string.usage_dialog_title) }
    private val usageSectionTitle: String = runBlocking { getString(Res.string.usage_dialog_usage_section_title) }
    private val usageUnavailable: String = runBlocking { getString(Res.string.usage_dialog_usage_unavailable) }

    /**
     * Builds an assistant message fixture carrying the given usage.
     *
     * @param usageStats Usage of the message, or `null` when none was reported.
     * @return Assistant message for the shared session.
     */
    private fun assistantMessage(usageStats: UsageStats?): ChatMessage.AssistantMessage =
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
            usageStats = usageStats
        )

    /**
     * Renders the dialog for the given message and names.
     *
     * @param message Message whose details are shown.
     * @param modelDisplayName Resolved model name, or `null` when it cannot be resolved.
     * @param settingsDisplayName Resolved settings-profile name, or `null` when it cannot be resolved.
     * @param onDismiss Invoked when the dialog asks to close.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.renderDialog(
        message: ChatMessage.AssistantMessage,
        modelDisplayName: String?,
        settingsDisplayName: String?,
        onDismiss: () -> Unit = {}
    ) {
        setContent {
            MessageUsageDialog(
                message = message,
                modelDisplayName = modelDisplayName,
                settingsDisplayName = settingsDisplayName,
                onDismiss = onDismiss
            )
        }
    }

    @Test
    fun `a message with reported usage shows every counter the provider reported`() = runComposeUiTest {
        val usage = UsageStats(
            inputTokens = 111,
            outputTokens = 222,
            totalTokens = 333,
            reasoningTokens = 44,
            cachedTokens = 55,
            cacheWriteTokens = 66
        )

        renderDialog(
            message = assistantMessage(usage),
            modelDisplayName = "Test Model",
            settingsDisplayName = "Default profile"
        )

        onNodeWithText(title).assertIsDisplayed()
        onNodeWithText("Test Model").assertIsDisplayed()
        onNodeWithText("Default profile").assertIsDisplayed()
        onNodeWithText(usageSectionTitle).assertIsDisplayed()

        val labelsAndValues = mapOf(
            getString(Res.string.usage_dialog_input_tokens_label) to "111",
            getString(Res.string.usage_dialog_output_tokens_label) to "222",
            getString(Res.string.usage_dialog_total_tokens_label) to "333",
            getString(Res.string.usage_dialog_reasoning_tokens_label) to "44",
            getString(Res.string.usage_dialog_cached_tokens_label) to "55",
            getString(Res.string.usage_dialog_cache_write_tokens_label) to "66"
        )
        labelsAndValues.forEach { (label, value) ->
            onNodeWithText("$label:").assertIsDisplayed()
            onNodeWithText(value).assertIsDisplayed()
        }
        onNodeWithText(usageUnavailable).assertDoesNotExist()
    }

    @Test
    fun `optional counters the provider omitted get no row of their own`() = runComposeUiTest {
        renderDialog(
            message = assistantMessage(UsageStats(inputTokens = 111, outputTokens = 222, totalTokens = 333)),
            modelDisplayName = "Test Model",
            settingsDisplayName = "Default profile"
        )

        onNodeWithText("${getString(Res.string.usage_dialog_input_tokens_label)}:").assertIsDisplayed()
        onNodeWithText("${getString(Res.string.usage_dialog_output_tokens_label)}:").assertIsDisplayed()
        onNodeWithText("${getString(Res.string.usage_dialog_total_tokens_label)}:").assertIsDisplayed()
        // An omitted counter must be absent from the dialog rather than shown as zero.
        onNodeWithText("${getString(Res.string.usage_dialog_reasoning_tokens_label)}:").assertDoesNotExist()
        onNodeWithText("${getString(Res.string.usage_dialog_cached_tokens_label)}:").assertDoesNotExist()
        onNodeWithText(
            "${getString(Res.string.usage_dialog_cache_write_tokens_label)}:"
        ).assertDoesNotExist()
        onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `a message without usage shows the explicit unavailable statement`() = runComposeUiTest {
        renderDialog(
            message = assistantMessage(usageStats = null),
            modelDisplayName = "Test Model",
            settingsDisplayName = "Default profile"
        )

        onNodeWithText(usageUnavailable).assertIsDisplayed()
        onNodeWithText("${getString(Res.string.usage_dialog_input_tokens_label)}:").assertDoesNotExist()
        onNodeWithText("${getString(Res.string.usage_dialog_total_tokens_label)}:").assertDoesNotExist()
    }

    @Test
    fun `unresolvable names fall back to the unknown value while the rest is shown`() = runComposeUiTest {
        renderDialog(
            message = assistantMessage(UsageStats(inputTokens = 111, outputTokens = 222, totalTokens = 333)),
            modelDisplayName = null,
            settingsDisplayName = null
        )

        onNodeWithText("$modelLabel:").assertIsDisplayed()
        onNodeWithText("$settingsLabel:").assertIsDisplayed()
        // Both names fall back, so the fallback text is rendered twice.
        assertEquals(
            2,
            onAllNodesWithText(unknownValue).fetchSemanticsNodes().size,
            "The model and the settings profile must each show the fallback"
        )
        // Missing names never block the rest of the dialog, including the usage it does have.
        onNodeWithText(usageSectionTitle).assertIsDisplayed()
        onNodeWithText("111").assertIsDisplayed()
    }
}
