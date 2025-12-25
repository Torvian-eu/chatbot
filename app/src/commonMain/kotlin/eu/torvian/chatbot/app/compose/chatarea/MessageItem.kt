package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.tool.ToolCall

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
 * @param modelsById Map of model IDs to LLMModel objects for displaying model names with graceful degradation.
 * @param toolCallsForMessage List of tool calls associated with this message.
 * @param onShowToolCallDetails Callback to show tool call details.
 * @param isCollapsed Whether this message is currently collapsed.
 * @param isCollapsible Whether this message can be collapsed (content length > threshold).
 * @param onToggleCollapse Callback to toggle the collapse state of this message.
 */
@Composable
fun MessageItem(
    message: ChatMessage,
    allMessagesMap: Map<Long, ChatMessage>,
    allRootMessageIds: List<Long>,
    messageActions: MessageActions,
    editingMessage: ChatMessage?,
    editingContent: String?,
    actions: ChatAreaActions,
    modelsById: Map<Long, LLMModel> = emptyMap(),
    toolCallsForMessage: List<ToolCall> = emptyList(),
    onShowToolCallDetails: (ToolCall) -> Unit = {},
    isCollapsed: Boolean = false,
    isCollapsible: Boolean = false,
    onToggleCollapse: () -> Unit = {}
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
        // Role and Name (e.g., "You:" or "AI:" or model name)
        val displayName = when (message.role) {
            ChatMessage.Role.USER -> "You"
            ChatMessage.Role.ASSISTANT -> {
                // Try to get model name from modelsById, fallback to "AI" or model ID
                (message as? ChatMessage.AssistantMessage)?.modelId?.let { modelId ->
                    modelsById[modelId]?.let { model ->
                        model.displayName ?: model.name
                    } ?: "Model ID: $modelId" // Graceful degradation
                } ?: "AI" // No model ID available
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$displayName:",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = contentColor.copy(alpha = 0.8f)
            )
            // Collapse/Expand button - only show for collapsible messages
            if (isCollapsible) {
                PlainTooltipBox(
                    text = if (isCollapsed) "Expand message" else "Collapse message",
                    showDelay = 500L
                ) {
                    IconButton(
                        onClick = onToggleCollapse,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (isCollapsed) "Expand message" else "Collapse message",
                            tint = contentColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // Message Content - conditionally show editing UI or display content
        MessageContent(
            message = message,
            isBeingEdited = editingMessage?.id == message.id,
            editingContent = if (editingMessage?.id == message.id) editingContent else null,
            actions = actions,
            contentColor = contentColor,
            isCollapsed = isCollapsed,
            onToggleCollapse = onToggleCollapse
        )

        // Tool Call Badges (for assistant messages)
        if (message.role == ChatMessage.Role.ASSISTANT && toolCallsForMessage.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ToolCallBadges(
                toolCalls = toolCallsForMessage,
                onToolCallClick = onShowToolCallDetails
            )
        }

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

