package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.common.models.ChatMessage
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for user interaction state operations.
 * Handles input content, editing, reply targeting, and sending state.
 */
interface InteractionState {

    // --- Read-only State Properties ---

    /**
     * The current text content in the message input field.
     */
    val inputContent: StateFlow<String>

    /**
     * The message the user is currently explicitly replying to via the Reply action.
     * If null, sending a message replies to the currentBranchLeafId value.
     */
    val replyTargetMessage: StateFlow<ChatMessage?>

    /**
     * The message currently being edited. Null if no message is being edited.
     */
    val editingMessage: StateFlow<ChatMessage?>

    /**
     * The content of the message being edited.
     */
    val editingContent: StateFlow<String>

    /**
     * Whether a message is currently being sent.
     */
    val isSendingMessage: StateFlow<Boolean>

    // --- State Mutation Methods ---

    /**
     * Sets the input content.
     */
    fun setInputContent(content: String)

    /**
     * Sets the reply target message.
     */
    fun setReplyTarget(message: ChatMessage?)

    /**
     * Sets the editing message.
     */
    fun setEditingMessage(message: ChatMessage?)

    /**
     * Sets the editing content.
     */
    fun setEditingContent(content: String)

    /**
     * Sets the sending message flag.
     */
    fun setIsSending(isSending: Boolean)
}
