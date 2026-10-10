package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.common.models.core.ChatMessage
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * Verifies the thread-affecting action gating of [MessageActionRow]: a compaction disables Edit and the
 * branch navigation controls, while an idle session keeps everything enabled (the running-turn
 * behaviour is covered by the existing message-action tests).
 */
@OptIn(ExperimentalTestApi::class)
class MessageActionRowTest {

    private val baseInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    /** First root of a two-branch session, the message whose row is rendered. */
    private val rootOne = ChatMessage.UserMessage(
        id = 1L,
        sessionId = 1L,
        content = "First root",
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = null,
        childrenMessageIds = emptyList()
    )

    /** Second root, which gives the rendered message branch navigation controls. */
    private val rootTwo = rootOne.copy(id = 2L, content = "Second root")

    /** Messages indexed by id, as [MessageActionRow] expects them. */
    private val allMessages = mapOf(rootOne.id to rootOne, rootTwo.id to rootTwo)

    /** Roots in the order the session lists them. */
    private val allRootMessageIds = listOf(rootOne.id, rootTwo.id)

    /**
     * Builds the action bundle the row reads, with every callback unused by these tests.
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
        onShowFileReferenceDetails = {},
        onShowMessageUsageDetails = {},
        onShowToolCallDetails = {}
    )

    /**
     * Renders one action row in the given turn state.
     *
     * @param turnExecutionState State the row reacts to.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.renderRow(turnExecutionState: TurnExecutionState) {
        setContent {
            MessageActionRow(
                message = rootOne,
                allMessagesMap = allMessages,
                allRootMessageIds = allRootMessageIds,
                messageActions = noOpActions(),
                hovered = true,
                turnExecutionState = turnExecutionState
            )
        }
    }

    @Test
    fun `a compaction disables editing and branch navigation`() = runComposeUiTest {
        renderRow(TurnExecutionState.COMPACTING)

        onNodeWithContentDescription("Edit message").assertIsNotEnabled()
        // The rendered root is the first of two branches, so only the forward control is offered.
        onNodeWithContentDescription("Next branch").assertExists()
        onNodeWithContentDescription("Next branch").assertIsNotEnabled()
        // The turn-scoped actions stay disabled as they are during a turn.
        onNodeWithContentDescription("Delete message").assertIsNotEnabled()
    }

    @Test
    fun `an idle session keeps editing and branch navigation enabled`() = runComposeUiTest {
        renderRow(TurnExecutionState.IDLE)

        onNodeWithContentDescription("Edit message").assertIsEnabled()
        onNodeWithContentDescription("Next branch").assertIsEnabled()
        onNodeWithContentDescription("Delete message").assertIsEnabled()
    }
}
