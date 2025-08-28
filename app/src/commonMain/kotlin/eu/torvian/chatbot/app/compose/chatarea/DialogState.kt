package eu.torvian.chatbot.app.compose.chatarea

import eu.torvian.chatbot.common.models.ChatMessage

/**
 * Consolidated state for all dialog management in the success state display.
 */
sealed class DialogState {
    object None : DialogState()
    data class DeleteMessage(val message: ChatMessage) : DialogState()
}