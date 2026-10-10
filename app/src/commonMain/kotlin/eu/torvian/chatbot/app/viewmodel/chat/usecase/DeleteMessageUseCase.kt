package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Use case for deleting a chat message.
 *
 * Owns the confirmation dialogs of the deletion, so the dialog and the deletion it confirms stay
 * together. The turn is checked again when the user confirms, because a generation can start while the
 * dialog is open.
 *
 * @property sessionRepository Repository the deletion is sent to.
 * @property state Shared chat state the dialogs are published into and the turn guard is read from.
 * @property scope Scope the confirmed deletion runs on.
 * @property notificationService Notification sink for repository errors.
 */
class DeleteMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val scope: CoroutineScope,
    private val notificationService: NotificationService
) {

    private val logger = kmpLogger<DeleteMessageUseCase>()

    /**
     * Shows the delete message confirmation dialog with pre-bound actions.
     *
     * @param message Message the user asked to delete.
     */
    fun requestDeleteMessage(message: ChatMessage) {
        state.setDialogState(
            ChatAreaDialogState.DeleteMessage(
                message = message,
                onDeleteConfirm = {
                    delete(message.id, recursive = false)
                },
                onDismiss = {
                    state.cancelDialog()
                }
            )
        )
    }

    /**
     * Shows the delete thread confirmation dialog with pre-bound actions.
     *
     * @param message Message the user asked to delete along with all of its replies.
     */
    fun requestDeleteMessageRecursively(message: ChatMessage) {
        state.setDialogState(
            ChatAreaDialogState.DeleteMessageRecursively(
                message = message,
                onDeleteConfirm = {
                    delete(message.id, recursive = true)
                },
                onDismiss = {
                    state.cancelDialog()
                }
            )
        )
    }

    /**
     * Deletes a message, or a message with all of its replies, for the confirmation that asked for it.
     *
     * @param messageId Message to delete.
     * @param recursive Whether the message's replies are deleted with it.
     */
    private fun delete(messageId: Long, recursive: Boolean) {
        // Deleting while a generation is streaming could corrupt the active conversation.
        if (state.isTurnActive) return
        scope.launch {
            val sessionId = state.activeSessionId.value ?: return@launch
            val action = if (recursive) "thread" else "message"
            logger.info("Deleting $action $messageId")

            val result = if (recursive) {
                sessionRepository.deleteMessageRecursively(messageId, sessionId)
            } else {
                sessionRepository.deleteMessage(messageId, sessionId)
            }

            result.fold(
                ifLeft = { repositoryError ->
                    logger.error("Delete $action repository error: ${repositoryError.message}")
                    notificationService.repositoryError(
                        error = repositoryError,
                        shortMessage = "Failed to delete $action"
                    )
                },
                ifRight = {
                    logger.info("Successfully deleted $action $messageId")
                    state.cancelDialog()
                }
            )
        }
    }
}
