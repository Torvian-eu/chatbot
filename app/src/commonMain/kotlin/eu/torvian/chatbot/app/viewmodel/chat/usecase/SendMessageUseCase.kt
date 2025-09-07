package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_sending_message_short
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.ChatStreamEvent
import eu.torvian.chatbot.common.models.ProcessNewMessageRequest

/**
 * Use case for sending messages in chat sessions.
 * Handles both streaming and non-streaming message sending based on settings.
 */
class SendMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val state: ChatState,
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

        val parentId = state.replyTargetMessage.value?.id ?: currentSession.currentLeafMessageId

        logger.info("Sending message to session ${currentSession.id}, parent: $parentId")

        state.setIsSending(true) // Set sending state to true

        try {
            // Check if streaming is enabled in settings, default to true if no settings available
            val isStreamingEnabled = currentSessionData.modelSettings?.stream ?: true

            val request = ProcessNewMessageRequest(content = content, parentMessageId = parentId)

            if (isStreamingEnabled) {
                handleStreamingMessage(currentSession.id, request)
            } else {
                handleNonStreamingMessage(currentSession.id, request)
            }
        } finally {
            state.setIsSending(false) // Always reset sending state
        }
    }

    /**
     * Handles streaming message processing using SessionRepository.
     */
    private suspend fun handleStreamingMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ) {
        sessionRepository.processNewMessageStreaming(sessionId, request).collect { eitherUpdate ->
            eitherUpdate.fold(
                ifLeft = { repositoryError ->
                    logger.error("Streaming message repository error: ${repositoryError.message}")
                    errorNotifier.repositoryError(
                        error = repositoryError,
                        shortMessageRes = Res.string.error_sending_message_short
                    )
                },
                ifRight = { chatUpdate ->
                    // Handle specific events that require UI state updates
                    when (chatUpdate) {
                        is ChatStreamEvent.UserMessageSaved -> {
                            // Clear input and reply target after user message is confirmed
                            state.setInputContent("")
                            state.setReplyTarget(null)
                        }

                        is ChatStreamEvent.ErrorOccurred -> {
                            errorNotifier.apiError(
                                error = chatUpdate.error,
                                shortMessageRes = Res.string.error_sending_message_short
                            )
                        }

                        else -> {
                            // Other events are handled by the repository's applyStreamEvent method
                            // No additional UI state updates needed
                        }
                    }
                }
            )
        }
    }

    /**
     * Handles non-streaming message processing using SessionRepository.
     */
    private suspend fun handleNonStreamingMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ) {
        sessionRepository.processNewMessage(sessionId, request).fold(
            ifLeft = { repositoryError ->
                logger.error("Send message repository error: ${repositoryError.message}")
                errorNotifier.repositoryError(
                    error = repositoryError,
                    shortMessageRes = Res.string.error_sending_message_short
                )
            },
            ifRight = {
                logger.info("Successfully sent non-streaming message")
                // Clear input and reply state
                state.setReplyTarget(null)
                state.setInputContent("")
            }
        )
    }
}
