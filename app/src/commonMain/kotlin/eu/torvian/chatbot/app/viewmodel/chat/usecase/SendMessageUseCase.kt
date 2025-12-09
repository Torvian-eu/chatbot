package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_sending_message_short
import eu.torvian.chatbot.app.generated.resources.warning_model_or_settings_unavailable
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.service.mcp.LocalMCPToolCallMediator
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.tool.LocalMCPToolCallRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Use case for sending messages in chat sessions.
 * Handles both streaming and non-streaming message sending based on settings.
 */
class SendMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val localMcpToolCallMediator: LocalMCPToolCallMediator,
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
        val currentSession = state.currentSession.value ?: return
        val content = state.inputContent.value.trim()
        if (content.isBlank()) return // Cannot send empty message

        // Check if model or model settings are available
        val currentModel = state.currentModel.value
        val currentSettings = state.currentSettings.value

        if (currentModel == null || currentSettings == null) {
            errorNotifier.genericWarning(
                shortMessageRes = Res.string.warning_model_or_settings_unavailable,
                detailedMessage = "Model: ${currentModel?.name ?: "not available"}, Settings: ${if (currentSettings != null) "available" else "not available"}"
            )
            return
        }

        val parentId = state.replyTargetMessage.value?.id ?: currentSession.currentLeafMessageId

        logger.info("Sending message to session ${currentSession.id}, parent: $parentId")

        state.setIsSending(true) // Set sending state to true

        try {
            // Check if streaming is enabled in settings
            val isStreamingEnabled = currentSettings.stream

            val request = ProcessNewMessageRequest(
                content = content,
                parentMessageId = parentId,
                isStreaming = isStreamingEnabled
            )

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
     * This function orchestrates the bidirectional flow of events for the WebSocket connection.
     */
    private suspend fun handleStreamingMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ) {
        // Create a flow to send local MCP tool call requests to the mediator.
        val mcpRequestFlow = MutableSharedFlow<LocalMCPToolCallRequest>()

        // The mediator processes requests from the flow and returns a flow of results.
        val mcpResultFlow = localMcpToolCallMediator.mediate(mcpRequestFlow)

        // Create the main client-to-server event flow by merging:
        //    a. The initial message request.
        //    b. The flow of tool results from the mediator, wrapped in the correct event type.
        val clientEvents = merge(
            flowOf(ChatClientEvent.ProcessNewMessage(request)),
            mcpResultFlow.map { ChatClientEvent.LocalMCPToolResult(it) }
        )

        // Call the repository with the combined event flow and collect server responses.
        sessionRepository.processNewMessageStreaming(sessionId, clientEvents).collect { eitherUpdate ->
            eitherUpdate.fold(
                ifLeft = { repositoryError ->
                    logger.error("Streaming message repository error: ${repositoryError.message}")
                    errorNotifier.repositoryError(
                        error = repositoryError,
                        shortMessageRes = Res.string.error_sending_message_short
                    )
                },
                ifRight = { chatUpdate ->
                    // Handle specific events that require UI state updates or further action.
                    when (chatUpdate) {
                        is ChatStreamEvent.UserMessageSaved -> {
                            // Clear input and reply target after user message is confirmed.
                            state.setInputContent("")
                            state.setReplyTarget(null)
                        }

                        is ChatStreamEvent.LocalMCPToolCallReceived -> {
                            // When the server requests a tool call, emit it to the request flow.
                            // This triggers the mediator to execute the tool.
                            logger.debug("Received local MCP tool call request: ${chatUpdate.request.toolName}")
                            mcpRequestFlow.emit(chatUpdate.request)
                        }

                        is ChatStreamEvent.ErrorOccurred -> {
                            errorNotifier.apiError(
                                error = chatUpdate.error,
                                shortMessageRes = Res.string.error_sending_message_short
                            )
                        }

                        else -> {
                            // Other events (e.g., delta, tool completed) are handled by the repository's
                            // applyStreamEvent method, which updates the UI state reactively.
                        }
                    }
                }
            )
        }
    }

    /**
     * Handles non-streaming message processing using SessionRepository.
     * This function orchestrates the bidirectional flow of events for the WebSocket connection.
     */
    private suspend fun handleNonStreamingMessage(
        sessionId: Long,
        request: ProcessNewMessageRequest
    ) {
        // Create a flow to send local MCP tool call requests to the mediator.
        val mcpRequestFlow = MutableSharedFlow<LocalMCPToolCallRequest>()

        // The mediator processes requests from the flow and returns a flow of results.
        val mcpResultFlow = localMcpToolCallMediator.mediate(mcpRequestFlow)

        // Create the main client-to-server event flow by merging:
        //  - The initial message request.
        //  - The flow of tool results from the mediator, wrapped in the correct event type.
        val clientEvents = merge(
            flowOf(ChatClientEvent.ProcessNewMessage(request)),
            mcpResultFlow.map { ChatClientEvent.LocalMCPToolResult(it) }
        )

        // Call the repository with the combined event flow and collect server responses.
        sessionRepository.processNewMessage(sessionId, clientEvents).collect { eitherEvent ->
            eitherEvent.fold(
                ifLeft = { repositoryError ->
                    logger.error("Non-streaming message repository error: ${repositoryError.message}")
                    errorNotifier.repositoryError(
                        error = repositoryError,
                        shortMessageRes = Res.string.error_sending_message_short
                    )
                },
                ifRight = { event ->
                    // Handle specific events that require UI state updates or further action.
                    when (event) {
                        is ChatEvent.UserMessageSaved -> {
                            // Clear input and reply target after user message is confirmed.
                            state.setInputContent("")
                            state.setReplyTarget(null)
                        }

                        is ChatEvent.LocalMCPToolCallReceived -> {
                            // When the server requests a tool call, emit it to the request flow.
                            // This triggers the mediator to execute the tool.
                            logger.debug("Received local MCP tool call request: ${event.request.toolName}")
                            mcpRequestFlow.emit(event.request)
                        }

                        is ChatEvent.ErrorOccurred -> {
                            errorNotifier.apiError(
                                error = event.error,
                                shortMessageRes = Res.string.error_sending_message_short
                            )
                        }

                        else -> {
                            // Other events (e.g., AssistantMessageSaved, StreamCompleted) are handled
                            // by the repository, which updates the UI state reactively.
                        }
                    }
                }
            )
        }
    }
}
