package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Consolidated state for all dialog management in the ChatArea.
 * This moves local dialog state from the compose layer to the ViewModel layer.
 *
 * Each dialog state includes pre-bound action lambdas to eliminate UI coupling
 * and provide a consistent action pattern.
 */
sealed class ChatAreaDialogState {
    /**
     * Represents that no dialog is currently visible.
     */
    object None : ChatAreaDialogState()

    /**
     * State for the "Delete Message" confirmation dialog.
     *
     * @property message The message targeted for deletion.
     * @property onDeleteConfirm Pre-bound action to execute when the user confirms deletion.
     * @property onDismiss Pre-bound action to execute when the dialog is dismissed.
     */
    data class DeleteMessage(
        val message: ChatMessage,
        val onDeleteConfirm: () -> Unit,
        val onDismiss: () -> Unit
    ) : ChatAreaDialogState()
}