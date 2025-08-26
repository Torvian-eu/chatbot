package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.SessionState

/**
 * Use case for deleting chat messages.
 * Handles message deletion including validation, API calls, and state updates.
 */
class DeleteMessageUseCase(
    private val chatApi: ChatApi,
    private val state: SessionState,
    private val loadSessionUseCase: LoadSessionUseCase,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<DeleteMessageUseCase>()

    /**
     * Deletes a specific message and its children from the session.
     * After successful deletion, reloads the session to update the UI state correctly.
     *
     * @param messageId The ID of the message to delete
     */
    suspend fun execute(messageId: Long) {
        val currentSession = state.currentSession ?: return

        logger.info("Deleting message $messageId from session ${currentSession.id}")

        chatApi.deleteMessage(messageId)
            .fold(
                ifLeft = { error ->
                    logger.error("Delete message API error: ${error.code} - ${error.message}")
                    errorNotifier.apiError(
                        error = error,
                        shortMessage = "Failed to delete message"
                    )
                },
                ifRight = {
                    logger.info("Successfully deleted message $messageId")
                    // Backend handled deletion recursively.
                    // Reload the session to update the UI state correctly.
                    loadSessionUseCase.execute(currentSession.id, forceReload = true)
                }
            )
    }
}
