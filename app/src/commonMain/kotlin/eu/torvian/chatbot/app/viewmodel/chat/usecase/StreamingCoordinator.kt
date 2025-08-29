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
import kotlinx.datetime.Clock

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
        logger.info("Starting streaming message for session ${currentSession.id}")

        chatApi.processNewMessageStreaming(
            sessionId = currentSession.id,
            request = ProcessNewMessageRequest(content = content, parentMessageId = parentId)
        ).collect { eitherUpdate ->
            eitherUpdate.fold(
                ifLeft = { error ->
                    logger.error("Streaming message API error: ${error.code} - ${error.message}")
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
        if (state.currentSession?.id != currentSession.id) return
        when (event) {
            is ChatStreamEvent.UserMessageSaved -> {
                logger.debug("User message saved: ${event.message.id}")
                // Add the user message to the session's messages and update parent's children list
                state.currentSession?.let { session ->
                    val updatedMessages = session.messages.map {
                        if (it.id == event.message.parentMessageId) {
                            val newChildren = it.childrenMessageIds + event.message.id
                            val now = Clock.System.now()
                            when (it) {
                                is ChatMessage.UserMessage -> it.copy(
                                    childrenMessageIds = newChildren,
                                    updatedAt = now
                                )
                                is ChatMessage.AssistantMessage -> it.copy(
                                    childrenMessageIds = newChildren,
                                    updatedAt = now
                                )
                            }
                        } else {
                            it
                        }
                    } + event.message
                    state.updateSessionMessages(updatedMessages)
                    state.updateSessionLeafId(event.message.id)
                }
                // Clear input and reply target after user message is confirmed
                state.setInputContent("")
                state.setReplyTarget(null)
            }

            is ChatStreamEvent.AssistantMessageStart -> {
                logger.debug("Assistant message started: ${event.assistantMessage.id}")
                // Add the assistant message to the session's messages
                state.currentSession?.let { session ->
                    val updatedMessages = session.messages + event.assistantMessage
                    state.updateSessionMessages(updatedMessages)
                    state.updateSessionLeafId(event.assistantMessage.id)
                }
            }

            is ChatStreamEvent.AssistantMessageDelta -> {
                logger.trace("Assistant message delta: ${event.deltaContent.length} chars")
                // Update the streaming message content
                state.currentSession?.let { session ->
                    val updatedMessages = session.messages.map {
                        if (it.id == event.messageId) {
                            val newContent = it.content + event.deltaContent
                            val now = Clock.System.now()
                            (it as ChatMessage.AssistantMessage).copy(content = newContent, updatedAt = now)
                        } else {
                            it
                        }
                    }
                    state.updateSessionMessages(updatedMessages)
                }
            }

            is ChatStreamEvent.AssistantMessageEnd -> {
                logger.info("Assistant message completed: ${event.finalAssistantMessage.id}")
                // Remove the temporary messages from the session's messages and add the final ones
                state.currentSession?.let { session ->
                    val updatedMessages = session.messages.filter {
                        it.id != event.tempMessageId && it.id != event.finalUserMessage.id
                    } + event.finalUserMessage + event.finalAssistantMessage
                    val newLeafId = event.finalAssistantMessage.id
                    state.updateSessionMessages(updatedMessages)
                    state.updateSessionLeafId(newLeafId)
                }
            }

            is ChatStreamEvent.ErrorOccurred -> {
                logger.error("Streaming error: ${event.error.message}")
                errorNotifier.apiError(
                    error = event.error,
                    shortMessageRes = Res.string.error_sending_message_short
                )
            }

            ChatStreamEvent.StreamCompleted -> {
                logger.info("Streaming completed for session ${state.currentSession?.id}")
            }
        }
    }
}
