package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.UpdateMessageRequest
import kotlinx.datetime.Clock

/**
 * Use case for editing chat messages.
 * Handles the editing workflow including validation, API calls, and state updates.
 */
class EditMessageUseCase(
    private val chatApi: ChatApi,
    private val state: ChatState,
    private val clock: Clock,
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
            // TODO: Consider emitting a validation error event
            return
        }

        val currentSession = state.sessionState.value.dataOrNull ?: return

        logger.info("Saving edited message ${messageToEdit.id}")

        chatApi.updateMessageContent(messageToEdit.id, UpdateMessageRequest(newContent))
            .fold(
                ifLeft = { error ->
                    logger.error("Edit message API error: ${error.code} - ${error.message}")
                    // TODO: Consider showing inline error for the edited message
                    errorNotifier.apiError(
                        error = error,
                        shortMessage = "Failed to save message edit"
                    )
                },
                ifRight = { updatedMessage ->
                    logger.info("Successfully saved edited message ${updatedMessage.id}")
                    // Update the message in the messages list within the current session
                    val updatedAllMessages = currentSession.messages.map {
                        if (it.id == updatedMessage.id) updatedMessage else it
                    }

                    // Update session with new messages and timestamp
                    val updatedSession = currentSession.copy(
                        messages = updatedAllMessages,
                        updatedAt = clock.now()
                    )
                    state.setSessionSuccess(updatedSession)

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
