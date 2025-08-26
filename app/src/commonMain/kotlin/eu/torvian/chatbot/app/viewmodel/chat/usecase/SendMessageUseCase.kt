package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_sending_message_short
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ProcessNewMessageRequest

/**
 * Use case for sending messages in chat sessions.
 * Handles both streaming and non-streaming message sending based on settings.
 */
class SendMessageUseCase(
    private val chatApi: ChatApi,
    private val state: ChatState,
    private val streamingCoordinator: StreamingCoordinator,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<SendMessageUseCase>()

    /**
     * Sends the current message content to the active session.
     * Determines the parent based on reply target or current branch leaf ID.
     * Branches on streaming settings to use appropriate processing method.
     */
    suspend fun execute() {
        val currentSessionData = state.currentSessionData ?: return
        val currentSession = currentSessionData.session
        val content = state.inputContent.value.trim()
        if (content.isBlank()) return // Cannot send empty message

        val parentId = state.replyTargetMessage.value?.id ?: state.currentBranchLeafId.value

        logger.info("Sending message to session ${currentSession.id}, parent: $parentId")

        state.setIsSending(true) // Set sending state to true

        try {
            // Check if streaming is enabled in settings, default to true if no settings available
            val isStreamingEnabled = currentSessionData.modelSettings?.stream ?: true

            if (isStreamingEnabled) {
                handleStreamingMessage(currentSession, content, parentId)
            } else {
                handleNonStreamingMessage(currentSession, content, parentId)
            }
        } finally {
            state.setIsSending(false) // Always reset sending state
        }
    }

    /**
     * Handles streaming message processing by delegating to StreamingCoordinator.
     */
    private suspend fun handleStreamingMessage(
        currentSession: ChatSession,
        content: String,
        parentId: Long?
    ) {
        streamingCoordinator.execute(currentSession, content, parentId)
    }

    /**
     * Handles non-streaming message processing.
     */
    private suspend fun handleNonStreamingMessage(
        currentSession: ChatSession,
        content: String,
        parentId: Long?
    ) {
        chatApi.processNewMessage(
            sessionId = currentSession.id,
            request = ProcessNewMessageRequest(content = content, parentMessageId = parentId)
        ).fold(
            ifLeft = { error ->
                logger.error("Send message API error: ${error.code} - ${error.message}")
                errorNotifier.apiError(
                    error = error,
                    shortMessageRes = Res.string.error_sending_message_short
                )
            },
            ifRight = { newMessages ->
                logger.info("Successfully sent non-streaming message, received ${newMessages.size} messages")
                // Add the new messages to the current session's messages
                val updatedMessages = currentSession.messages + newMessages
                val newLeafId = newMessages.lastOrNull()?.id

                // Update the session state with new messages
                state.updateSessionMessages(updatedMessages, newLeafId)
                state.setReplyTarget(null)
                state.setInputContent("")
            }
        )
    }
}
