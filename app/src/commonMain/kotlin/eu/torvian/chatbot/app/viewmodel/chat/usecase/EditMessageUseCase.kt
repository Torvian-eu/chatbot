package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.core.UpdateMessageRequest
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.MessageInsertPosition

/**
 * Use case for editing chat messages.
 * Handles the editing workflow including validation, repository calls, and state updates.
 */
class EditMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
    private val notificationService: NotificationService
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
        val newContent = state.editingContent.value

        logger.info("Saving edited message ${messageToEdit.id}")

        sessionRepository.updateMessageContent(
            messageToEdit.id,
            messageToEdit.sessionId,
            UpdateMessageRequest(newContent)
        )
            .fold(
                ifLeft = { repositoryError ->
                    logger.error("Edit message repository error: ${repositoryError.message}")
                    notificationService.repositoryError(
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
     * Saves the edited message content as a new copy (sibling).
     * Creates a new branch in the conversation.
     */
    suspend fun saveAsCopy() {
        val messageToEdit = state.editingMessage.value ?: return
        val newContent = state.editingContent.value

        val parentId = messageToEdit.parentMessageId
        val session = state.currentSession.value ?: return
        val modelId = session.currentModelId
        val settingsId = session.currentSettingsId

        logger.info("Saving edited message ${messageToEdit.id} as copy (sibling)")

        sessionRepository.insertMessage(
            sessionId = session.id,
            targetMessageId = parentId,
            position = MessageInsertPosition.APPEND,
            role = messageToEdit.role,
            content = newContent,
            modelId = if (messageToEdit.role == ChatMessage.Role.ASSISTANT) modelId else null,
            settingsId = if (messageToEdit.role == ChatMessage.Role.ASSISTANT) settingsId else null
        ).fold(
            ifLeft = { error ->
                notificationService.repositoryError(error, "Failed to save copy")
            },
            ifRight = {
                logger.info("Successfully saved copy")
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
