package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_sending_message_short
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.ChatStreamEvent
import eu.torvian.chatbot.common.models.ProcessNewMessageRequest

/**
 * Coordinates streaming message processing.
 * Handles the streaming flow lifecycle and updates ChatState accordingly.
 */
class StreamingCoordinator(
    private val chatApi: ChatApi,
    private val state: ChatState,
    private val errorNotifier: ErrorNotifier
) {

    private val logger = kmpLogger<StreamingCoordinator>()

    /**
     * Executes streaming message processing for the given session and content.
     *
     * @param currentSession The current chat session
     * @param content The message content to send
     * @param parentId The parent message ID for threading
     */
    suspend fun execute(currentSession: ChatSession, content: String, parentId: Long?) {
        // Clear any previous streaming state
        state.setStreamingUserMessage(null)
        state.setStreamingAssistantMessage(null)

        logger.info("Starting streaming message for session ${currentSession.id}")

        chatApi.processNewMessageStreaming(
            sessionId = currentSession.id,
            request = ProcessNewMessageRequest(content = content, parentMessageId = parentId)
        ).collect { eitherUpdate ->
            eitherUpdate.fold(
                ifLeft = { error ->
                    logger.error("Streaming message API error: ${error.code} - ${error.message}")
                    // Clear any streaming state and emit error
                    clearStreamingState()
                    errorNotifier.apiError(
                        error = error,
                        shortMessageRes = Res.string.error_sending_message_short
                    )
                },
                ifRight = { chatUpdate ->
                    handleStreamingEvent(chatUpdate, currentSession)
                }
            )
        }
    }

    /**
     * Handles individual streaming events from the API.
     */
    private suspend fun handleStreamingEvent(event: ChatStreamEvent, currentSession: ChatSession) {
        when (event) {
            is ChatStreamEvent.UserMessageSaved -> {
                logger.debug("User message saved: ${event.message.id}")
                // Store the user message in the temporary streaming state
                state.setStreamingUserMessage(event.message)
                state.setCurrentLeafId(event.message.id)
                // Clear input and reply target after user message is confirmed
                state.setInputContent("")
                state.setReplyTarget(null)
            }

            is ChatStreamEvent.AssistantMessageStart -> {
                logger.debug("Assistant message started: ${event.assistantMessage.id}")
                // Use the assistant message directly from the update
                state.setCurrentLeafId(event.assistantMessage.id)
                state.setStreamingAssistantMessage(event.assistantMessage)
            }

            is ChatStreamEvent.AssistantMessageDelta -> {
                logger.trace("Assistant message delta: ${event.deltaContent.length} chars")
                // Update the streaming message content
                val currentStreamingMessage = state.displayedMessages.value
                    .filterIsInstance<ChatMessage.AssistantMessage>()
                    .find { it.id == event.messageId }

                currentStreamingMessage?.let { streamingMessage ->
                    state.setStreamingAssistantMessage(
                        streamingMessage.copy(
                            content = streamingMessage.content + event.deltaContent
                        )
                    )
                }
            }

            is ChatStreamEvent.AssistantMessageEnd -> {
                logger.info("Assistant message completed: ${event.finalAssistantMessage.id}")
                // Update the session with the new messages
                val updatedMessages = currentSession.messages + event.finalUserMessage + event.finalAssistantMessage
                val newLeafId = event.finalAssistantMessage.id

                state.updateSessionMessages(updatedMessages, newLeafId)
                // Clear streaming state
                clearStreamingState()
            }

            is ChatStreamEvent.ErrorOccurred -> {
                logger.error("Streaming error: ${event.error.message}")
                clearStreamingState()
                errorNotifier.apiError(
                    error = event.error,
                    shortMessageRes = Res.string.error_sending_message_short
                )
            }

            ChatStreamEvent.StreamCompleted -> {
                logger.info("Streaming completed for session ${currentSession.id}")
            }
        }
    }

    /**
     * Clears all streaming state.
     */
    private fun clearStreamingState() {
        state.setStreamingUserMessage(null)
        state.setStreamingAssistantMessage(null)
    }
}
