package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.api.core.UpdateMessageRequest

/**
 * Use case for editing chat messages.
 * Handles the editing workflow including validation, repository calls, and state updates.
 */
class EditMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<EditMessageUseCase>()

    /**
     * Starts editing a message by setting the editing state.
     *
     * @param message The message to edit
     */
    fun start(message: ChatMessage) {
        logger.info("Starting to edit message ${message.id}")
        state.setEditingMessage(message)
        state.setEditingContent(message.content)
    }

    /**
     * Updates the content of the message currently being edited.
     * Called by the UI as the user types in the editing input field.
     *
     * @param newText The new text content for the editing field
     */
    fun updateContent(newText: String) {
        state.setEditingContent(newText)
    }

    /**
     * Saves the edited message content to the server.
     */
    suspend fun save() {
        val messageToEdit = state.editingMessage.value ?: return
        val newContent = state.editingContent.value.trim()

        if (newContent.isBlank()) {
            logger.warn("Validation Error: Message content cannot be empty.")
            return
        }

        logger.info("Saving edited message ${messageToEdit.id}")

        sessionRepository.updateMessageContent(
            messageToEdit.id,
            messageToEdit.sessionId,
            UpdateMessageRequest(newContent)
        )
            .fold(
                ifLeft = { repositoryError ->
                    logger.error("Edit message repository error: ${repositoryError.message}")
                    errorNotifier.repositoryError(
                        error = repositoryError,
                        shortMessage = "Failed to save message edit"
                    )
                },
                ifRight = {
                    logger.info("Successfully saved edited message ${messageToEdit.id}")
                    // Clear editing state on success
                    cancel()
                }
            )
    }

    /**
     * Cancels the message editing state.
     */
    fun cancel() {
        logger.info("Cancelling message editing")
        state.setEditingMessage(null)
        state.setEditingContent("")
    }
}
