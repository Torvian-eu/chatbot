package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.LoadingOverlay
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.viewmodel.ChatAreaActions
import eu.torvian.chatbot.app.viewmodel.ChatAreaState
import eu.torvian.chatbot.app.viewmodel.UiState
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession

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

/**
 * Data class to hold all potential actions that can be performed on a chat message.
 * Callbacks are nullable as not all actions might be implemented or applicable yet.
 * This pattern allows for clear API definition while maintaining flexibility for phased implementation.
 *
 * @param onSwitchBranchToMessage Callback to switch to a different thread branch.
 * @param onEditMessage Callback for when the user wants to edit a message.
 * @param onCopyMessage Callback for when the user wants to copy message content to clipboard.
 * @param onRegenerateMessage Callback for when the user wants to regenerate an assistant message.
 */
private data class MessageActions(
    val onSwitchBranchToMessage: (Long) -> Unit,
    val onEditMessage: ((ChatMessage) -> Unit)? = null,
    val onCopyMessage: ((ChatMessage) -> Unit)? = null,
    val onRegenerateMessage: ((ChatMessage) -> Unit)? = null
)

/**
 * Composable for the main chat message display area.
 * Handles displaying messages, loading/error states, and threading indicators.
 * (PR 20: Implement Chat Area UI (Message Display) (E1.S*))
 *
 * @param state The current UI state contract for the chat area.
 * @param actions The actions contract for the chat area.
 */
@Composable
fun ChatArea(
    state: ChatAreaState,
    actions: ChatAreaActions
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state.sessionUiState) {
            UiState.Loading -> LoadingStateDisplay(modifier = Modifier.fillMaxSize())
            is UiState.Error -> ErrorStateDisplay(
                mainMessage = "Failed to load chat session",
                detailMessage = state.sessionUiState.error.message,
                onRetry = { /* Retry logic will be implemented later, as ChatAreaActions does not directly support it */ },
                modifier = Modifier.align(Alignment.Center)
            )

            UiState.Idle -> IdleStateDisplay(modifier = Modifier.align(Alignment.Center))
            is UiState.Success -> SuccessStateDisplay(
                chatSession = state.sessionUiState.data,
                displayedMessages = state.displayedMessages,
                actions = actions // Pass the full actions contract
            )
        }
    }
}

/**
 * Displays a loading overlay.
 */
@Composable
private fun LoadingStateDisplay(modifier: Modifier = Modifier) {
    LoadingOverlay(modifier = modifier)
}

/**
 * Displays the idle state message when no session is selected.
 */
@Composable
private fun IdleStateDisplay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Select a session from the left or create a new one.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Messages will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Displays the loaded chat session with messages.
 *
 * @param chatSession The current chat session data.
 * @param displayedMessages The list of messages to display.
 * @param actions The actions contract for the chat area, providing message-related callbacks.
 */
@Composable
private fun SuccessStateDisplay(
    chatSession: ChatSession,
    displayedMessages: List<ChatMessage>,
    actions: ChatAreaActions // Use the full actions for better future flexibility
) {
    val allMessagesMap = remember(chatSession.messages) {
        chatSession.messages.associateBy { it.id }
    }
    val allRootMessageIds = remember(chatSession.messages) {
        chatSession.messages.filter { it.parentMessageId == null }
            .sortedBy { it.createdAt } // Ensure consistent order for roots
            .map { it.id }
    }

    // Prepare message actions to pass down
    val messageActions = remember(actions) {
        MessageActions(
            onSwitchBranchToMessage = actions::onSwitchBranchToMessage,
            // Future actions - uncomment and pass actions from `actions` contract when implemented:
            // onEditMessage = actions::onStartEditing,
            // onCopyMessage = actions::onCopyMessageContent,
            // onRegenerateMessage = actions::onRegenerateAssistantMessage
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp), // Space for future input area
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = displayedMessages,
            key = { it.id } // Provide a key for efficient recomposition
        ) { message ->
            val branchNavData = remember(message, allMessagesMap, allRootMessageIds) {
                getBranchNavigationData(
                    message = message,
                    allMessagesMap = allMessagesMap,
                    allRootMessageIds = allRootMessageIds
                )
            }
            MessageItem(
                message = message,
                branchNavigationData = branchNavData,
                messageActions = messageActions
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
 * Composable for displaying a single chat message.
 *
 * @param message The ChatMessage to display.
 * @param branchNavigationData Pre-calculated data for branch navigation.
 * @param messageActions All actions that can be performed on this message.
 */
@Composable
private fun MessageItem(
    message: ChatMessage,
    branchNavigationData: BranchNavigationData,
    messageActions: MessageActions
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val containerColor = when (message.role) {
        ChatMessage.Role.USER -> MaterialTheme.colorScheme.surfaceContainerLow
        ChatMessage.Role.ASSISTANT -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (message.role) {
        ChatMessage.Role.USER -> MaterialTheme.colorScheme.onSurfaceVariant
        ChatMessage.Role.ASSISTANT -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .hoverable(interactionSource)
            .padding(12.dp)
    ) {
        // Role and Name (e.g., "You:" or "AI:")
        Text(
            text = "${if (message.role == ChatMessage.Role.USER) "You" else "AI"}:",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = contentColor.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(4.dp))
        // Message Content
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor
        )

        // All message actions (branch navigation and future controls)
        Spacer(Modifier.height(8.dp))
        MessageActionRow(
            message = message,
            branchNavigationData = branchNavigationData,
            messageActions = messageActions,
            hovered = hovered,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp) // Reserve the height for the action row
        )
    }
}

/**
 * Displays a row of action controls for a message, visible on hover.
 * This includes general actions (Edit, Copy, Regenerate) and branch navigation.
 *
 * @param message The [ChatMessage] for which controls are displayed (used for role-specific actions).
 * @param branchNavigationData Pre-calculated data for branch navigation.
 * @param messageActions All available actions for the message.
 * @param hovered Whether the parent [MessageItem] is currently hovered.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
private fun MessageActionRow(
    message: ChatMessage,
    branchNavigationData: BranchNavigationData,
    messageActions: MessageActions,
    hovered: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        if (hovered) { // Only compose the Row when hovered
            Row(
                modifier = Modifier.fillMaxSize(), // Fill the Box
                horizontalArrangement = Arrangement.SpaceBetween, // Pushes start actions to left, end actions to right
                verticalAlignment = Alignment.CenterVertically
            ) {
                // General actions aligned to the start
                GeneralMessageControls(message = message, messageActions = messageActions)

                // Branch Navigation Controls aligned to the end
                if (branchNavigationData.showNavigation) {
                    BranchNavigationControls(
                        branchNavigationData = branchNavigationData,
                        onSwitchBranchToMessage = messageActions.onSwitchBranchToMessage
                    )
                }
            }
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp) // Spacing between action icons
    ) {
        // Edit Button (User Message only)
        if (message.role == ChatMessage.Role.USER) {
            EditButton(message = message, onEditMessage = messageActions.onEditMessage)
        }

        // Copy Button (All messages)
        CopyButton(message = message, onCopyMessage = messageActions.onCopyMessage)

        // Regenerate Button (Assistant Message only)
        if (message.role == ChatMessage.Role.ASSISTANT) {
            RegenerateButton(message = message, onRegenerateMessage = messageActions.onRegenerateMessage)
        }
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
    modifier: Modifier = Modifier
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
 * Composable that displays an error state.
 * This is a generic error display component.
 *
 * @param mainMessage A prominent message describing the error type (e.g., "Failed to load").
 * @param detailMessage A more specific, detailed message about the error.
 * @param onRetry Callback for when a retry action is requested. Currently, no button is shown.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
private fun ErrorStateDisplay(
    mainMessage: String,
    detailMessage: String,
    onRetry: () -> Unit, // Kept for future extensibility, as no retry button is currently rendered.
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = mainMessage,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = detailMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        // Removed the Retry button here for now, as ChatAreaActions does not directly support it
        // and reloading the session needs a sessionId, which is not direct here.
        // Will revisit global error handling / retry mechanisms in a later polish PR if needed.
        /*
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
        */
    }
}