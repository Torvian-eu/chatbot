package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.viewmodel.chat.state.InteractionState
import eu.torvian.chatbot.common.models.ChatMessage

/**
 * Use case for managing reply functionality in chat.
 */
class ReplyUseCase(
    private val state: InteractionState
) {

    /**
     * Sets the state to indicate the user is replying to a specific message.
     *
     * @param message The message to reply to
     */
    fun start(message: ChatMessage) {
        state.setReplyTarget(message)
    }

    /**
     * Cancels the specific reply target, reverting to replying to the current leaf.
     */
    fun cancel() {
        state.setReplyTarget(null)
    }
}
