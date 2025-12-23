package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier

/**
 * Use case for deleting a chat message.
 */
class DeleteMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<DeleteMessageUseCase>()

    /**
     * Deletes a specific message from the session.
     *
     * @param messageId The ID of the message to delete
     * @param recursive If true, deletes the message and all its descendants (thread).
     *                  If false, deletes only the message, promoting children to parent.
     */
    suspend fun execute(messageId: Long, recursive: Boolean = false) {
        val sessionId = state.activeSessionId.value ?: return
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
                errorNotifier.repositoryError(
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
