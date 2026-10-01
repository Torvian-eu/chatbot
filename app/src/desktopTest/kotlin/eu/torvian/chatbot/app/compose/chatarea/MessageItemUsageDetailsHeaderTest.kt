package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.LLMModel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Verifies which message headers open the details dialog.
 *
 * The dialog is only useful once a generation reached a terminal state, so only the header of such an assistant
 * message is clickable: a still-streaming placeholder offers nothing to show, and user messages have no generation
 * to describe.
 */
@OptIn(ExperimentalTestApi::class)
class MessageItemUsageDetailsHeaderTest {

    private val baseInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /** Model whose display name is resolved in the header of the rendered assistant message. */
    private val model = LLMModel(
        id = 5L,
        name = "gpt-4o",
        providerId = 1L,
        active = true,
        displayName = "Test Model"
    )

    /** Header text rendered for [model], used to target the click. */
    private val modelHeaderText = "${model.displayName}:"

    /**
     * Builds an assistant message fixture of the shared session.
     *
     * @param isComplete Whether the generation finished normally.
     * @param incompleteCause Terminal cause of a generation that did not finish normally, or `null` when none
     *        is known (a completed message or a still-running placeholder).
     * @return Assistant message produced by [model].
     */
    private fun assistantMessage(
        isComplete: Boolean,
        incompleteCause: AssistantMessageIncompleteCause? = null
    ): ChatMessage.AssistantMessage = ChatMessage.AssistantMessage(
        id = 2L,
        sessionId = 1L,
        content = "Answer",
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = null,
        childrenMessageIds = emptyList(),
        modelId = model.id,
        settingsId = null,
        isComplete = isComplete,
        incompleteCause = incompleteCause
    )

    /**
     * Builds the action bundle the item reads, capturing the details-dialog callback.
     *
     * @param onShowMessageUsageDetails Callback invoked when a clickable header is clicked.
     * @return Message actions whose other callbacks do nothing.
     */
    private fun actions(
        onShowMessageUsageDetails: (ChatMessage.AssistantMessage) -> Unit
    ): MessageActions = MessageActions(
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
        onShowMessageUsageDetails = onShowMessageUsageDetails
    )

    @Test
    fun `clicking the header of a completed assistant message opens the details dialog`() = runComposeUiTest {
        val message = assistantMessage(isComplete = true)
        var shown: ChatMessage.AssistantMessage? = null
        setContent {
            MessageItem(
                message = message,
                allMessagesMap = mapOf(message.id to message),
                allRootMessageIds = listOf(message.id),
                actions = actions { shown = it },
                editingMessage = null,
                editingContent = null,
                editingFileReferences = emptyList(),
                editingBasePathOverride = null,
                modelsById = mapOf(model.id to model)
            )
        }

        onNodeWithText(modelHeaderText).performClick()

        assertEquals(message, shown)
    }

    @Test
    fun `clicking the header of an interrupted assistant message opens the details dialog`() = runComposeUiTest {
        val message = assistantMessage(
            isComplete = false,
            incompleteCause = AssistantMessageIncompleteCause.INTERRUPTED_BY_USER
        )
        var shown: ChatMessage.AssistantMessage? = null
        setContent {
            MessageItem(
                message = message,
                allMessagesMap = mapOf(message.id to message),
                allRootMessageIds = listOf(message.id),
                actions = actions { shown = it },
                editingMessage = null,
                editingContent = null,
                editingFileReferences = emptyList(),
                editingBasePathOverride = null,
                modelsById = mapOf(model.id to model)
            )
        }

        onNodeWithText(modelHeaderText).performClick()

        assertEquals(message, shown)
    }

    @Test
    fun `clicking the header of a still-streaming assistant message does nothing`() = runComposeUiTest {
        val message = assistantMessage(isComplete = false)
        var shown: ChatMessage.AssistantMessage? = null
        setContent {
            MessageItem(
                message = message,
                allMessagesMap = mapOf(message.id to message),
                allRootMessageIds = listOf(message.id),
                actions = actions { shown = it },
                editingMessage = null,
                editingContent = null,
                editingFileReferences = emptyList(),
                editingBasePathOverride = null,
                modelsById = mapOf(model.id to model)
            )
        }

        onNodeWithText(modelHeaderText).performClick()

        assertNull(shown, "A generation that is still running has no details to show")
    }

    @Test
    fun `clicking a user message header does nothing`() = runComposeUiTest {
        val message = ChatMessage.UserMessage(
            id = 1L,
            sessionId = 1L,
            content = "Hello",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = listOf(2L)
        )
        var shown: ChatMessage.AssistantMessage? = null
        setContent {
            MessageItem(
                message = message,
                allMessagesMap = mapOf(message.id to message),
                allRootMessageIds = listOf(message.id),
                actions = actions { shown = it },
                editingMessage = null,
                editingContent = null,
                editingFileReferences = emptyList(),
                editingBasePathOverride = null
            )
        }

        onNodeWithText("You:").performClick()

        assertNull(shown)
    }
}
