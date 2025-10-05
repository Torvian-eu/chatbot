package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Displays a row of action controls for a message.
 * General actions (Edit, Copy, Regenerate) are visible on hover, while branch navigation is always visible.
 *
 * @param message The [ChatMessage] for which controls are displayed (used for role-specific actions).
 * @param allMessagesMap A map of all messages in the session for efficient lookup.
 * @param allRootMessageIds A sorted list of all root message IDs in the session.
 * @param messageActions All available actions for the message.
 * @param hovered Whether the parent [MessageItem] is currently hovered.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
fun MessageActionRow(
    message: ChatMessage,
    allMessagesMap: Map<Long, ChatMessage>,
    allRootMessageIds: List<Long>,
    messageActions: MessageActions,
    hovered: Boolean,
    modifier: Modifier = Modifier
) {
    // Calculate branch navigation data within MessageActionRow
    val branchNavData = remember(message, allMessagesMap, allRootMessageIds) {
        getBranchNavigationData(
            message = message,
            allMessagesMap = allMessagesMap,
            allRootMessageIds = allRootMessageIds
        )
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween, // Pushes start actions to left, end actions to right
        verticalAlignment = Alignment.CenterVertically
    ) {
        // General actions aligned to the start - only visible on hover
        if (hovered) {
            GeneralMessageControls(message = message, messageActions = messageActions)
        } else {
            Spacer(Modifier.width(0.dp)) // Placeholder to maintain layout structure
        }

        // Branch Navigation Controls aligned to the end - always visible when navigation is available
        if (branchNavData.showNavigation) {
            BranchNavigationControls(
                branchNavigationData = branchNavData,
                onSwitchBranchToMessage = messageActions.onSwitchBranchToMessage
            )
        }
    }
}

/**
 * Displays the general action buttons for a message (e.g., Edit, Copy, Regenerate).
 *
 * @param message The [ChatMessage] for which controls are displayed.
 * @param messageActions All available actions for the message.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
private fun GeneralMessageControls(
    message: ChatMessage,
    messageActions: MessageActions,
    modifier: Modifier = Modifier.Companion
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp) // Spacing between action icons
    ) {
        // Edit Button
        EditButton(message = message, onEditMessage = messageActions.onEditMessage)

        // Copy Button
        CopyButton(message = message, onCopyMessage = messageActions.onCopyMessage)

        // Regenerate Button (Assistant Message only)
        if (message.role == ChatMessage.Role.ASSISTANT) {
            RegenerateButton(message = message, onRegenerateMessage = messageActions.onRegenerateMessage)
        }

        // Reply Button
        ReplyButton(message = message, onReplyMessage = messageActions.onReplyMessage)

        // Delete Button
        DeleteButton(message = message, onDeleteMessage = messageActions.onDeleteMessage)
    }
}

/**
 * Displays the Edit message button.
 *
 * @param message The message to be edited.
 * @param onEditMessage Callback for the edit action, can be null if not implemented.
 */
@Composable
private fun EditButton(message: ChatMessage, onEditMessage: ((ChatMessage) -> Unit)?) {
    if (onEditMessage != null) {
        PlainTooltipBox(text = "Edit message") {
            IconButton(
                onClick = { onEditMessage(message) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit message",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Displays the Copy message content button.
 *
 * @param message The message whose content is to be copied.
 * @param onCopyMessage Callback for the copy action, can be null if not implemented.
 */
@Composable
private fun CopyButton(message: ChatMessage, onCopyMessage: ((ChatMessage) -> Unit)?) {
    if (onCopyMessage != null) {
        PlainTooltipBox(text = "Copy message content") {
            IconButton(
                onClick = { onCopyMessage(message) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy message content",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Displays the Regenerate message button for assistant messages.
 *
 * @param message The assistant message to be regenerated.
 * @param onRegenerateMessage Callback for the regenerate action, can be null if not implemented.
 */
@Composable
private fun RegenerateButton(message: ChatMessage, onRegenerateMessage: ((ChatMessage) -> Unit)?) {
    if (onRegenerateMessage != null) {
        PlainTooltipBox(text = "Regenerate response") {
            IconButton(
                onClick = { onRegenerateMessage(message) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Regenerate response",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Displays the Reply message button.
 *
 * @param message The message to which a reply is being composed.
 * @param onReplyMessage Callback for the reply action, can be null if not implemented.
 */
@Composable
private fun ReplyButton(message: ChatMessage, onReplyMessage: ((ChatMessage) -> Unit)?) {
    if (onReplyMessage != null) {
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
}

/**
 * Displays the Delete message button.
 *
 * @param message The message to be deleted.
 * @param onDeleteMessage Callback for the delete action, can be null if not implemented.
 */
@Composable
private fun DeleteButton(message: ChatMessage, onDeleteMessage: ((ChatMessage) -> Unit)?) {
    if (onDeleteMessage != null) {
        PlainTooltipBox(text = "Delete message") {
            IconButton(
                onClick = { onDeleteMessage(message) },
                modifier = Modifier.size(24.dp)
            ) {
                // Using a standard trash icon for deletion
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete message",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Displays the branch navigation controls (previous, next, and count).
 *
 * @param branchNavigationData Pre-calculated data for branch navigation.
 * @param onSwitchBranchToMessage Callback to switch to a different thread branch.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
private fun BranchNavigationControls(
    branchNavigationData: BranchNavigationData,
    onSwitchBranchToMessage: (Long) -> Unit,
    modifier: Modifier = Modifier.Companion
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Previous branch button '<'
        if (branchNavigationData.showPrev) {
            PlainTooltipBox(text = "Previous branch") {
                IconButton(
                    onClick = {
                        val prevIdx = branchNavigationData.zeroBasedIndex - 1
                        onSwitchBranchToMessage(branchNavigationData.alternativeBranchMessageIds[prevIdx])
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = "Previous branch",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Spacer(Modifier.size(24.dp)) // Maintain spacing if button is hidden
        }

        // Current / Total count (1-based for UI)
        Text(
            text = "${branchNavigationData.zeroBasedIndex + 1} / ${branchNavigationData.totalBranches}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp) // Small padding around text
        )

        // Next branch button '>'
        if (branchNavigationData.showNext) {
            PlainTooltipBox(text = "Next branch") {
                IconButton(
                    onClick = {
                        val nextIdx = branchNavigationData.zeroBasedIndex + 1
                        onSwitchBranchToMessage(branchNavigationData.alternativeBranchMessageIds[nextIdx])
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = "Next branch",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Spacer(Modifier.size(24.dp)) // Maintain spacing if button is hidden
        }
    }
}

/**
 * Helper function to determine the branch navigation data for a given message.
 *
 * @param message The current message being evaluated.
 * @param allMessagesMap A map of all messages in the session for efficient lookup.
 * @param allRootMessageIds A sorted list of all root message IDs in the session.
 * @return [BranchNavigationData] containing alternatives, current index, and total.
 */
private fun getBranchNavigationData(
    message: ChatMessage,
    allMessagesMap: Map<Long, ChatMessage>,
    allRootMessageIds: List<Long>
): BranchNavigationData {
    val alternativeBranchMessageIds: List<Long>

    if (message.parentMessageId != null) {
        // Case 1: Message has a parent -> alternatives are children of its parent
        val parentMessage = allMessagesMap[message.parentMessageId]
        if (parentMessage != null) {
            // Filter out deleted/non-existent children and sort for consistent ordering
            alternativeBranchMessageIds = parentMessage.childrenMessageIds
                .mapNotNull { allMessagesMap[it] }
                .sortedBy { it.createdAt } // Consistent order, e.g., by creation time
                .map { it.id }
        } else {
            // Parent not found, fall back to no navigation
            return BranchNavigationData(emptyList(), 0, 0)
        }
    } else {
        // Case 2: Message is a root message -> alternatives are other root messages (including itself)
        // allRootMessageIds is already sorted.
        alternativeBranchMessageIds = allRootMessageIds
    }

    if (alternativeBranchMessageIds.size <= 1) {
        // No alternatives if there's only one or zero options
        return BranchNavigationData(emptyList(), 0, 0)
    }

    // Determine the current index (zero-based)
    val indexOfCurrentAlternative: Int = alternativeBranchMessageIds.indexOf(message.id)

    if (indexOfCurrentAlternative == -1) {
        // This should theoretically not happen if `message` is part of `displayedMessages`
        // and `displayedMessages` correctly represents a branch from `alternativeBranchMessageIds`.
        // However, as a safeguard:
        return BranchNavigationData(emptyList(), 0, 0)
    }

    return BranchNavigationData(
        alternativeBranchMessageIds = alternativeBranchMessageIds,
        zeroBasedIndex = indexOfCurrentAlternative,
        totalBranches = alternativeBranchMessageIds.size
    )
}

/**
 * Data class to hold branch navigation information for a message.
 *
 * @param alternativeBranchMessageIds The IDs of the alternative branch messages.
 * @param zeroBasedIndex The zero-based index of the current message in the alternative branch.
 * @param totalBranches The total number of branches for this message item.
 */
private data class BranchNavigationData(
    val alternativeBranchMessageIds: List<Long>,
    val zeroBasedIndex: Int,
    val totalBranches: Int
) {
    val showNavigation: Boolean get() = totalBranches > 1
    val showPrev: Boolean get() = zeroBasedIndex > 0
    val showNext: Boolean get() = zeroBasedIndex < totalBranches - 1
}