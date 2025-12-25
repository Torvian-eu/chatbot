package eu.torvian.chatbot.app.compose.chatarea

import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Data class holding all possible message actions that can be performed.
 *
 * @param onSwitchBranchToMessage Callback for when the user wants to switch branch to a specific message.
 * @param onEditMessage Callback for when the user wants to edit a specific message.
 * @param onCopyMessage Callback for when the user wants to copy message content.
 * @param onRegenerateMessage Callback for when the user wants to regenerate an assistant message.
 * @param onReplyMessage Callback for when the user wants to reply to a specific message.
 * @param onDeleteMessage Callback for when the user wants to delete a specific message (single).
 * @param onDeleteThread Callback for when the user wants to delete a message and all its replies (recursive).
 */
data class MessageActions(
    val onSwitchBranchToMessage: (Long) -> Unit,
    val onEditMessage: (ChatMessage) -> Unit,
    val onCopyMessage: ((ChatMessage) -> Unit)? = null,
    val onRegenerateMessage: ((ChatMessage) -> Unit)? = null,
    val onReplyMessage: (ChatMessage) -> Unit,
    val onDeleteMessage: (ChatMessage) -> Unit,
    val onDeleteThread: (ChatMessage) -> Unit,
    val onRequestInsertMessage: (ChatMessage) -> Unit
)