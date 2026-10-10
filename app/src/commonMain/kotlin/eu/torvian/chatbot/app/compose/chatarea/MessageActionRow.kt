package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Displays a row of action controls for a message.
 * General actions (Edit, Copy, Regenerate) are visible on hover, while branch navigation is always visible.
 *
 * @param message The [ChatMessage] for which controls are displayed (used for role-specific actions).
 * @param allMessagesMap A map of all messages in the session for efficient lookup.
 * @param allRootMessageIds A sorted list of all root message IDs in the session.
 * @param messageActions All available actions for the message item.
 * @param hovered Whether the parent [MessageItem] is currently hovered.
 * @param turnExecutionState Lifecycle state of the active assistant turn. Actions that start a
 * new LLM turn (Regenerate, Branch & Continue) are disabled while a turn is active, and every
 * thread-affecting action (including Edit and branch navigation) is disabled while a compaction runs.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
fun MessageActionRow(
    message: ChatMessage,
    allMessagesMap: Map<Long, ChatMessage>,
    allRootMessageIds: List<Long>,
    messageActions: MessageActions,
    hovered: Boolean,
    modifier: Modifier = Modifier,
    turnExecutionState: TurnExecutionState = TurnExecutionState.IDLE
) {
    // Track if the "More" menu is expanded to keep controls visible
    var moreMenuExpanded by remember { mutableStateOf(false) }

    // Only a compaction blocks these actions: a running turn keeps today's behaviour, where the thread
    // stays editable.
    val threadEditable = turnExecutionState != TurnExecutionState.COMPACTING

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
        // General actions aligned to the start - visible on hover or when menu is open
        if (hovered || moreMenuExpanded) {
            GeneralMessageControls(
                message = message,
                messageActions = messageActions,
                moreMenuExpanded = moreMenuExpanded,
                onMoreMenuExpandedChange = { moreMenuExpanded = it },
                turnExecutionState = turnExecutionState,
            )
        } else {
            Spacer(Modifier.width(0.dp)) // Placeholder to maintain layout structure
        }

        // Branch Navigation Controls aligned to the end - always visible when navigation is available
        if (branchNavData.showNavigation) {
            BranchNavigationControls(
                branchNavigationData = branchNavData,
                onSwitchBranchToMessage = messageActions.onSwitchBranchToMessage,
                enabled = threadEditable
            )
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
internal data class BranchNavigationData(
    val alternativeBranchMessageIds: List<Long>,
    val zeroBasedIndex: Int,
    val totalBranches: Int
) {
    val showNavigation: Boolean get() = totalBranches > 1
    val showPrev: Boolean get() = zeroBasedIndex > 0
    val showNext: Boolean get() = zeroBasedIndex < totalBranches - 1
}
