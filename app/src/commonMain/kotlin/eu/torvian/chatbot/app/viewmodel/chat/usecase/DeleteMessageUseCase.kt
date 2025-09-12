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
     */
    suspend fun execute(messageId: Long) {
        val sessionId = state.activeSessionId.value ?: return
        logger.info("Deleting message $messageId")

        sessionRepository.deleteMessage(messageId, sessionId)
            .fold(
                ifLeft = { repositoryError ->
                    logger.error("Delete message repository error: ${repositoryError.message}")
                    errorNotifier.repositoryError(
                        error = repositoryError,
                        shortMessage = "Failed to delete message"
                    )
                },
                ifRight = {
                    logger.info("Successfully deleted message $messageId")
                    state.cancelDialog()
                }
            )
    }
}
