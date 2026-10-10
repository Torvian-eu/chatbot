package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Displays the general action buttons for a message (e.g., Edit, Copy, Regenerate).
 *
 * @param message The [ChatMessage] for which controls are displayed.
 * @param messageActions All available actions for the message item.
 * @param moreMenuExpanded Whether the "More" menu is currently expanded.
 * @param onMoreMenuExpandedChange Callback to update the expanded state of the "More" menu.
 * @param turnExecutionState Lifecycle state of the active assistant turn; used to disable
 * actions that would start a conflicting LLM turn.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
internal fun GeneralMessageControls(
    message: ChatMessage,
    messageActions: MessageActions,
    moreMenuExpanded: Boolean,
    onMoreMenuExpandedChange: (Boolean) -> Unit,
    turnExecutionState: TurnExecutionState,
    modifier: Modifier = Modifier
) {
    // Thread-affecting controls stay enabled during a running turn; only a compaction locks the thread.
    val threadEditable = turnExecutionState != TurnExecutionState.COMPACTING

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp) // Spacing between action icons
    ) {
        // Edit Button
        EditButton(
            message = message,
            onEditMessage = messageActions.onEditMessage,
            enabled = threadEditable
        )

        // Reply Button
        ReplyButton(message = message, onReplyMessage = messageActions.onReplyMessage)

        // Delete Button
        DeleteButton(
            message = message,
            onDeleteMessage = messageActions.onDeleteMessage,
            enabled = turnExecutionState == TurnExecutionState.IDLE
        )

        // Copy Button
        CopyButton(message = message, onCopyMessage = messageActions.onCopyMessage)

        // Regenerate Button (Assistant Message only)
        if (message.role == ChatMessage.Role.ASSISTANT) {
            RegenerateButton(
                message = message,
                onRegenerateMessage = messageActions.onRegenerateMessage,
                enabled = turnExecutionState == TurnExecutionState.IDLE
            )
        }

        // Branch & Continue is frequent enough to remain one click away from the action row.
        BranchAndContinueButton(
            message = message,
            onBranchAndContinue = messageActions.onBranchAndContinue,
            enabled = turnExecutionState == TurnExecutionState.IDLE
        )

        // More Actions Menu (Insert Message, Delete Thread, etc.)
        MessageMoreActionsMenu(
            message = message,
            messageActions = messageActions,
            expanded = moreMenuExpanded,
            onExpandedChange = onMoreMenuExpandedChange,
            enabled = turnExecutionState == TurnExecutionState.IDLE
        )
    }
}

/**
 * Displays the Edit message button.
 *
 * @param message The message to be edited.
 * @param onEditMessage Callback for the edit action.
 * @param enabled Whether the edit action may run; false while a compaction owns the thread.
 */
@Composable
internal fun EditButton(
    message: ChatMessage,
    onEditMessage: (ChatMessage) -> Unit,
    enabled: Boolean
) {
    PlainTooltipBox(text = "Edit message") {
        IconButton(
            onClick = { onEditMessage(message) },
            modifier = Modifier.size(24.dp),
            enabled = enabled
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit message",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Displays the Copy message content button.
 *
 * @param message The message whose content is to be copied.
 * @param onCopyMessage Callback for the copy action.
 */
@Composable
internal fun CopyButton(message: ChatMessage, onCopyMessage: ((ChatMessage) -> Unit)) {
    PlainTooltipBox(text = "Copy message content") {
        IconButton(
            onClick = { onCopyMessage(message) },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy message content",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Displays the Regenerate message button for assistant messages.
 * The button is hidden if the message has no parent (root message edge case).
 *
 * @param message The assistant message to be regenerated.
 * @param onRegenerateMessage Callback for the regenerate action.
 * @param enabled Whether the button is clickable. Disabled while an assistant turn is active
 * to prevent starting a conflicting generation.
 */
@Composable
internal fun RegenerateButton(
    message: ChatMessage,
    onRegenerateMessage: (ChatMessage) -> Unit,
    enabled: Boolean
) {
    // Only show if message has a parent (not root)
    if (message.parentMessageId != null) {
        PlainTooltipBox(text = "Regenerate response") {
            IconButton(
                onClick = { onRegenerateMessage(message) },
                modifier = Modifier.size(24.dp),
                enabled = enabled
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Regenerate response",
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    }
                )
            }
        }
    }
}

/**
 * Displays the Reply message button.
 *
 * @param message The message to which a reply is being composed.
 * @param onReplyMessage Callback for the reply action.
 */
@Composable
internal fun ReplyButton(message: ChatMessage, onReplyMessage: (ChatMessage) -> Unit) {
    PlainTooltipBox(text = "Reply to message") {
        IconButton(
            onClick = { onReplyMessage(message) },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Reply,
                contentDescription = "Reply to message",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Displays the Delete message button.
 *
 * @param message The message to be deleted.
 * @param onDeleteMessage Callback for the delete action.
 * @param enabled Whether the button is clickable. Disabled while an assistant turn is active
 * to prevent destructive edits to a conversation that is still being generated.
 */
@Composable
internal fun DeleteButton(
    message: ChatMessage,
    onDeleteMessage: (ChatMessage) -> Unit,
    enabled: Boolean
) {
    PlainTooltipBox(text = "Delete message") {
        IconButton(
            onClick = { onDeleteMessage(message) },
            modifier = Modifier.size(24.dp),
            enabled = enabled
        ) {
            // Using a standard trash icon for deletion
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete message",
                tint = if (enabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
                }
            )
        }
    }
}

/**
 * Displays the direct action for creating a new continuation branch from a message.
 *
 * The callback reuses the established Branch & Continue flow, so moving the control
 * out of the overflow menu changes only discoverability and not request semantics.
 *
 * @param message The message from which the new branch should continue.
 * @param onBranchAndContinue Callback invoked with [message] when the action is selected.
 * @param enabled Whether the button is clickable. Disabled while an assistant turn is active
 * to prevent starting a conflicting generation.
 */
@Composable
internal fun BranchAndContinueButton(
    message: ChatMessage,
    onBranchAndContinue: (ChatMessage) -> Unit,
    enabled: Boolean
) {
    PlainTooltipBox(text = "Branch & Continue") {
        IconButton(
            onClick = { onBranchAndContinue(message) },
            modifier = Modifier.size(24.dp),
            enabled = enabled
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Branch & Continue",
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }
    }
}
