package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.ChatMessage

/**
 * Composable for the main chat message display area.
 * Handles displaying messages, loading/error states, and threading indicators.
 * (PR 20: Implement Chat Area UI (Message Display) (E1.S*))
 *
 * @param message The message data.
 * @param allMessagesMap A map of all messages in the session for efficient lookup.
 * @param allRootMessageIds A sorted list of all root message IDs in the session.
 * @param messageActions All available actions for the message.
 * @param editingMessage The message currently being edited (E3.S1, E3.S2).
 * @param editingContent The content of the message currently being edited (E3.S1, E3.S2).
 * @param actions The actions contract for the chat area.
 */
@Composable
fun MessageItem(
    message: ChatMessage,
    allMessagesMap: Map<Long, ChatMessage>,
    allRootMessageIds: List<Long>,
    messageActions: MessageActions,
    editingMessage: ChatMessage?,
    editingContent: String?,
    actions: ChatAreaActions
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

        // Message Content - conditionally show editing UI or display content
        MessageContent(
            message = message,
            isBeingEdited = editingMessage?.id == message.id,
            editingContent = if (editingMessage?.id == message.id) editingContent else null,
            actions = actions,
            contentColor = contentColor
        )

        // All message actions (branch navigation and future controls)
        Spacer(Modifier.height(8.dp))
        MessageActionRow(
            message = message,
            allMessagesMap = allMessagesMap,
            allRootMessageIds = allRootMessageIds,
            messageActions = messageActions,
            hovered = hovered,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp) // Reserve the height for the action row
        )
    }
}

