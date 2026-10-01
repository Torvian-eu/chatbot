package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.reasoning_section_label
import eu.torvian.chatbot.app.generated.resources.reasoning_thinking_indicator
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.getString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies when the reasoning section of a message is rendered at all, and how its header behaves while the turn is
 * still running.
 *
 * The section is driven by the message alone: it exists once the message has reasoning to show, and a still-running
 * generation adds the thinking indicator only while no answer text has arrived. A generation that never produced
 * reasoning must therefore show no header, which is the behaviour these tests pin down — the derivation itself is
 * covered separately. The section is also part of the message body, so collapsing the message takes it away without
 * discarding its own expansion state.
 */
@OptIn(ExperimentalTestApi::class)
class MessageItemReasoningSectionTest {

    /** Localized header label, resolved instead of hardcoded so the test does not depend on the machine locale. */
    private val reasoningLabel: String = runBlocking { getString(Res.string.reasoning_section_label) }

    /** Localized streaming indicator shown in the header while the turn runs. */
    private val thinkingIndicator: String = runBlocking { getString(Res.string.reasoning_thinking_indicator) }

    private val baseInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /** Plaintext reasoning text carried by the fixtures, asserted on to detect the expanded body. */
    private val reasoningText = "the model reasoned about this"

    /**
     * Builds the reasoning item a streamed delta produces, in the shape the client caches and the server persists.
     *
     * @param text Plaintext reasoning to hold.
     * @return Reasoning item with one `reasoning_text` part.
     */
    private fun reasoningItem(text: String): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "reasoning_text")
                        put("text", text)
                    }
                )
            }
        )
    }

    /**
     * Builds an assistant message fixture.
     *
     * @param reasoningItems Reasoning items carried by the message, or `null` when it received none.
     * @param isComplete Whether the message is finished (a still-running placeholder is not).
     * @param content Answer text already received; empty while the model is still reasoning.
     * @return Assistant message for the shared session.
     */
    private fun assistantMessage(
        reasoningItems: List<JsonObject>?,
        isComplete: Boolean,
        content: String = ""
    ): ChatMessage.AssistantMessage = ChatMessage.AssistantMessage(
        id = 2L,
        sessionId = 1L,
        content = content,
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = null,
        childrenMessageIds = emptyList(),
        modelId = null,
        settingsId = null,
        reasoningItems = reasoningItems,
        isComplete = isComplete
    )

    /**
     * Builds the action bundle the item reads, with every callback unused by these tests.
     *
     * @return No-op message actions.
     */
    private fun noOpActions(): MessageActions = MessageActions(
        onSwitchBranchToMessage = {},
        onEditMessage = {},
        onCopyMessage = {},
        onRegenerateMessage = {},
        onReplyMessage = {},
        onDeleteMessage = {},
        onDeleteThread = {},
        onRequestInsertMessage = {},
        onUpdateEditingContent = {},
        onSaveEditing = {},
        onSaveEditingAsCopy = {},
        onCancelEditing = {},
        onAddEditingFileReferences = {},
        onRemoveEditingFileReference = {},
        onToggleEditingFileContent = { _, _ -> },
        onSetEditingBasePathOverride = {},
        onResetEditingBasePath = {},
        onBranchAndContinue = {},
        onToggleMessageCollapsed = {},
        onToggleReasoningSection = {},
        onShowToolCallDetails = {},
        onShowFileReferenceDetails = {},
        onShowMessageUsageDetails = {}
    )

    /**
     * Renders one message item with the given reasoning expansion state.
     *
     * The expansion state is passed as a provider so it is read inside the composition, which is what lets a test
     * drive it from its own state and observe the section react to the toggle.
     *
     * @param message Message to render.
     * @param isReasoningExpanded Provider of the current expansion state, read during composition.
     * @param onToggleReasoningSection Callback invoked when the section header is clicked.
     * @param isCollapsed Provider of the current message collapse state, read during composition.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.renderMessageItem(
        message: ChatMessage,
        isReasoningExpanded: () -> Boolean = { false },
        onToggleReasoningSection: () -> Unit = {},
        isCollapsed: () -> Boolean = { false }
    ) {
        setContent {
            MessageItem(
                message = message,
                allMessagesMap = mapOf(message.id to message),
                allRootMessageIds = listOf(message.id),
                actions = noOpActions(),
                editingMessage = null,
                editingContent = null,
                editingFileReferences = emptyList(),
                editingBasePathOverride = null,
                isCollapsed = isCollapsed(),
                isReasoningExpanded = isReasoningExpanded(),
                onToggleReasoningSection = { onToggleReasoningSection() }
            )
        }
    }

    @Test
    fun `an in-flight message without reasoning renders no section and no indicator`() = runComposeUiTest {
        renderMessageItem(assistantMessage(reasoningItems = null, isComplete = false))

        onNodeWithText(reasoningLabel).assertDoesNotExist()
        onNodeWithText(thinkingIndicator).assertDoesNotExist()
    }

    @Test
    fun `a completed message without reasoning renders no section and no indicator`() = runComposeUiTest {
        renderMessageItem(assistantMessage(reasoningItems = null, isComplete = true))

        onNodeWithText(reasoningLabel).assertDoesNotExist()
        onNodeWithText(thinkingIndicator).assertDoesNotExist()
    }

    @Test
    fun `an in-flight message with streamed reasoning shows the section and the indicator`() = runComposeUiTest {
        renderMessageItem(
            assistantMessage(reasoningItems = listOf(reasoningItem(reasoningText)), isComplete = false)
        )

        onNodeWithText(reasoningLabel).assertIsDisplayed()
        onNodeWithText(thinkingIndicator).assertIsDisplayed()
    }

    @Test
    fun `an in-flight message that already streams answer text shows the section without the indicator`() =
        runComposeUiTest {
            renderMessageItem(
                assistantMessage(
                    reasoningItems = listOf(reasoningItem(reasoningText)),
                    isComplete = false,
                    content = "Partial answer"
                )
            )

            onNodeWithText(reasoningLabel).assertIsDisplayed()
            // The answer has started, so the model has left its reasoning phase and the header must not claim one.
            onNodeWithText(thinkingIndicator).assertDoesNotExist()
        }

    @Test
    fun `a completed message with reasoning shows the section without the indicator`() = runComposeUiTest {
        renderMessageItem(
            assistantMessage(reasoningItems = listOf(reasoningItem(reasoningText)), isComplete = true)
        )

        onNodeWithText(reasoningLabel).assertIsDisplayed()
        // The indicator describes an activity that has stopped, so the completed header must not claim one.
        onNodeWithText(thinkingIndicator).assertDoesNotExist()
    }

    @Test
    fun `reasoning is collapsed by default and expands on click`() = runComposeUiTest {
        var isExpanded by mutableStateOf(false)
        var toggleCount = 0
        renderMessageItem(
            message = assistantMessage(reasoningItems = listOf(reasoningItem(reasoningText)), isComplete = false),
            isReasoningExpanded = { isExpanded },
            onToggleReasoningSection = {
                toggleCount++
                isExpanded = !isExpanded
            }
        )

        // Collapsed by default: the reasoning text is not in the tree at all.
        onNodeWithText(reasoningText).assertDoesNotExist()

        onNodeWithText(reasoningLabel).performClick()

        assertEquals(1, toggleCount)
        assertTrue(isExpanded)
        onNodeWithText(reasoningText).assertIsDisplayed()
    }

    @Test
    fun `a collapsed message hides its reasoning section`() = runComposeUiTest {
        renderMessageItem(
            message = assistantMessage(reasoningItems = listOf(reasoningItem(reasoningText)), isComplete = true),
            isReasoningExpanded = { true },
            isCollapsed = { true }
        )

        // Collapsing truncates the answer, so the reasoning must not be left standing beside it.
        onNodeWithText(reasoningLabel).assertDoesNotExist()
        onNodeWithText(reasoningText).assertDoesNotExist()
    }

    @Test
    fun `expanding a collapsed message brings the reasoning section back in its previous state`() =
        runComposeUiTest {
            var isCollapsed by mutableStateOf(true)
            renderMessageItem(
                message = assistantMessage(reasoningItems = listOf(reasoningItem(reasoningText)), isComplete = true),
                isReasoningExpanded = { true },
                isCollapsed = { isCollapsed }
            )

            onNodeWithText(reasoningText).assertDoesNotExist()

            isCollapsed = false
            waitForIdle()

            // The section comes back expanded, because the collapse must not consume the user's expansion choice.
            onNodeWithText(reasoningLabel).assertIsDisplayed()
            onNodeWithText(reasoningText).assertIsDisplayed()
        }
}
