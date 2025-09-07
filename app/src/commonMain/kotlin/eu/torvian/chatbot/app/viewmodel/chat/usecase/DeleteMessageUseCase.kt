package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState

/**
 * Use case for deleting chat messages in the reactive architecture.
 *
 * This use case follows the action-only pattern where it:
 * 1. Deletes the message via repository
 * 2. The repository automatically updates the cached session
 */
class DeleteMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<DeleteMessageUseCase>()

    /**
     * Deletes a specific message and its children from the session.
     * The repository automatically updates the cached session.
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
                }
            )
    }
}
