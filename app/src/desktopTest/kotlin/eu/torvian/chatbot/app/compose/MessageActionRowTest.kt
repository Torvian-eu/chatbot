package eu.torvian.chatbot.app.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.compose.chatarea.MessageActionRow
import eu.torvian.chatbot.app.compose.chatarea.MessageActions
import eu.torvian.chatbot.app.testutils.data.assistantMessage
import eu.torvian.chatbot.app.testutils.data.userMessage
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.common.models.core.ChatMessage
import org.junit.jupiter.api.Test

/**
 * Tests for [MessageActionRow] turn-aware action availability.
 *
 * Actions that start a new LLM turn (Regenerate response, Branch & Continue) or that mutate the
 * conversation structure (Delete message, Insert Message, Delete Thread) must be disabled while
 * an assistant turn is active so users cannot start a conflicting generation or edit a
 * conversation that is still being generated.
 */
@OptIn(ExperimentalTestApi::class)
class MessageActionRowTest {

    /**
     * Builds a [MessageActions] instance whose relevant callbacks increment the given counters.
     *
     * Plain counters are used instead of a mocking framework because the row reads the callback
     * properties during composition; a mock would record those getter reads and pollute
     * "never called" verifications.
     *
     * @param onRegenerateMessage Counter incremented when the Regenerate action fires.
     * @param onBranchAndContinue Counter incremented when the Branch & Continue action fires.
     * @param onDeleteMessage Counter incremented when the Delete message action fires.
     * @param onDeleteThread Counter incremented when the Delete Thread action fires.
     * @param onRequestInsertMessage Counter incremented when the Insert Message action fires.
     */
    private fun stubMessageActions(
        onRegenerateMessage: () -> Unit = {},
        onBranchAndContinue: () -> Unit = {},
        onDeleteMessage: () -> Unit = {},
        onDeleteThread: () -> Unit = {},
        onRequestInsertMessage: () -> Unit = {}
    ): MessageActions = MessageActions(
        onSwitchBranchToMessage = {},
        onEditMessage = {},
        onCopyMessage = {},
        onRegenerateMessage = { onRegenerateMessage() },
        onReplyMessage = {},
        onDeleteMessage = { onDeleteMessage() },
        onDeleteThread = { onDeleteThread() },
        onRequestInsertMessage = { onRequestInsertMessage() },
        onUpdateEditingContent = {},
        onSaveEditing = {},
        onSaveEditingAsCopy = {},
        onCancelEditing = {},
        onAddEditingFileReferences = {},
        onRemoveEditingFileReference = {},
        onToggleEditingFileContent = { _, _ -> },
        onSetEditingBasePathOverride = {},
        onResetEditingBasePath = {},
        onBranchAndContinue = { onBranchAndContinue() },
        onToggleMessageCollapsed = {},
        onShowToolCallDetails = {},
        onShowFileReferenceDetails = {}
    )

    /**
     * Opens the "More actions" overflow menu by clicking its trigger button.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.openMoreActionsMenu() {
        onNodeWithContentDescription("More actions").performClick()
    }

    @Test
    fun turnIdle_allActions_areEnabled() = runComposeUiTest {
        // Arrange - an assistant message with a parent so the Regenerate button is shown
        val parent = userMessage(id = 1L, sessionId = 1L, content = "Parent")
        val assistant = assistantMessage(
            id = 2L,
            sessionId = 1L,
            content = "Response",
            parentMessageId = 1L
        )
        var regenerateCalls = 0
        var branchCalls = 0
        var deleteCalls = 0
        var deleteThreadCalls = 0
        var insertCalls = 0
        val actions = stubMessageActions(
            onRegenerateMessage = { regenerateCalls++ },
            onBranchAndContinue = { branchCalls++ },
            onDeleteMessage = { deleteCalls++ },
            onDeleteThread = { deleteThreadCalls++ },
            onRequestInsertMessage = { insertCalls++ }
        )

        setContent {
            MessageActionRow(
                message = assistant,
                allMessagesMap = mapOf(1L to parent, 2L to assistant),
                allRootMessageIds = listOf(1L),
                messageActions = actions,
                hovered = true,
                turnExecutionState = TurnExecutionState.IDLE
            )
        }

        // Assert - Regenerate, Branch & Continue, and Delete are visible and clickable
        onNodeWithContentDescription("Regenerate response").assertIsDisplayed().assertIsEnabled()
        onNodeWithContentDescription("Branch & Continue").assertIsDisplayed().assertIsEnabled()
        onNodeWithContentDescription("Delete message").assertIsDisplayed().assertIsEnabled()

        // Act - trigger the general controls
        onNodeWithContentDescription("Regenerate response").performClick()
        onNodeWithContentDescription("Branch & Continue").performClick()
        onNodeWithContentDescription("Delete message").performClick()

        // Assert - general callbacks fired
        check(regenerateCalls == 1) { "Expected 1 regenerate call, got $regenerateCalls" }
        check(branchCalls == 1) { "Expected 1 branch call, got $branchCalls" }
        check(deleteCalls == 1) { "Expected 1 delete call, got $deleteCalls" }

        // Act - trigger Insert Message from the overflow menu (menu closes on selection)
        openMoreActionsMenu()
        onNodeWithText("Insert Message").assertIsDisplayed().assertIsEnabled()
        onNodeWithText("Insert Message").performClick()

        // Act - reopen the menu and trigger Delete Thread
        openMoreActionsMenu()
        onNodeWithText("Delete Thread").assertIsDisplayed().assertIsEnabled()
        onNodeWithText("Delete Thread").performClick()

        // Assert - overflow callbacks fired
        check(insertCalls == 1) { "Expected 1 insert call, got $insertCalls" }
        check(deleteThreadCalls == 1) { "Expected 1 delete thread call, got $deleteThreadCalls" }
    }

    @Test
    fun turnRunning_allActions_areDisabled() = runComposeUiTest {
        // Arrange - an assistant message with a parent so the Regenerate button is shown
        val parent = userMessage(id = 1L, sessionId = 1L, content = "Parent")
        val assistant = assistantMessage(
            id = 2L,
            sessionId = 1L,
            content = "Response",
            parentMessageId = 1L
        )
        var regenerateCalls = 0
        var branchCalls = 0
        var deleteCalls = 0
        var deleteThreadCalls = 0
        var insertCalls = 0
        val actions = stubMessageActions(
            onRegenerateMessage = { regenerateCalls++ },
            onBranchAndContinue = { branchCalls++ },
            onDeleteMessage = { deleteCalls++ },
            onDeleteThread = { deleteThreadCalls++ },
            onRequestInsertMessage = { insertCalls++ }
        )

        setContent {
            MessageActionRow(
                message = assistant,
                allMessagesMap = mapOf(1L to parent, 2L to assistant),
                allRootMessageIds = listOf(1L),
                messageActions = actions,
                hovered = true,
                turnExecutionState = TurnExecutionState.RUNNING
            )
        }

        // Assert - Regenerate, Branch & Continue, and Delete are visible but disabled
        onNodeWithContentDescription("Regenerate response").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithContentDescription("Branch & Continue").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithContentDescription("Delete message").assertIsDisplayed().assertIsNotEnabled()

        // Act - attempt to trigger the general controls
        onNodeWithContentDescription("Regenerate response").performClick()
        onNodeWithContentDescription("Branch & Continue").performClick()
        onNodeWithContentDescription("Delete message").performClick()

        // Assert - no general callbacks fired
        check(regenerateCalls == 0) { "Expected no regenerate call, got $regenerateCalls" }
        check(branchCalls == 0) { "Expected no branch call, got $branchCalls" }
        check(deleteCalls == 0) { "Expected no delete call, got $deleteCalls" }

        // Act - attempt to trigger the overflow actions (menu stays open because items are disabled)
        openMoreActionsMenu()
        onNodeWithText("Insert Message").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText("Delete Thread").assertIsDisplayed().assertIsNotEnabled()

        // Assert - no overflow callbacks fired
        check(insertCalls == 0) { "Expected no insert call, got $insertCalls" }
        check(deleteThreadCalls == 0) { "Expected no delete thread call, got $deleteThreadCalls" }
    }

    @Test
    fun turnPausing_allActions_areDisabled() = runComposeUiTest {
        // Arrange - an assistant message with a parent so the Regenerate button is shown
        val parent = userMessage(id = 1L, sessionId = 1L, content = "Parent")
        val assistant = assistantMessage(
            id = 2L,
            sessionId = 1L,
            content = "Response",
            parentMessageId = 1L
        )
        val actions = stubMessageActions()

        setContent {
            MessageActionRow(
                message = assistant,
                allMessagesMap = mapOf(1L to parent, 2L to assistant),
                allRootMessageIds = listOf(1L),
                messageActions = actions,
                hovered = true,
                turnExecutionState = TurnExecutionState.PAUSING
            )
        }

        // Assert - general actions remain disabled while pausing
        onNodeWithContentDescription("Regenerate response").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithContentDescription("Branch & Continue").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithContentDescription("Delete message").assertIsDisplayed().assertIsNotEnabled()

        // Assert - overflow actions remain disabled while pausing
        openMoreActionsMenu()
        onNodeWithText("Insert Message").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText("Delete Thread").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun turnStopping_allActions_areDisabled() = runComposeUiTest {
        // Arrange - an assistant message with a parent so the Regenerate button is shown
        val parent = userMessage(id = 1L, sessionId = 1L, content = "Parent")
        val assistant = assistantMessage(
            id = 2L,
            sessionId = 1L,
            content = "Response",
            parentMessageId = 1L
        )
        val actions = stubMessageActions()

        setContent {
            MessageActionRow(
                message = assistant,
                allMessagesMap = mapOf(1L to parent, 2L to assistant),
                allRootMessageIds = listOf(1L),
                messageActions = actions,
                hovered = true,
                turnExecutionState = TurnExecutionState.STOPPING
            )
        }

        // Assert - general actions remain disabled while stopping
        onNodeWithContentDescription("Regenerate response").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithContentDescription("Branch & Continue").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithContentDescription("Delete message").assertIsDisplayed().assertIsNotEnabled()

        // Assert - overflow actions remain disabled while stopping
        openMoreActionsMenu()
        onNodeWithText("Insert Message").assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText("Delete Thread").assertIsDisplayed().assertIsNotEnabled()
    }
}
