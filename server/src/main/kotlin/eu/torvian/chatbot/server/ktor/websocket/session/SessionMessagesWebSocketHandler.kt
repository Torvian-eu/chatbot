package eu.torvian.chatbot.server.ktor.websocket.session

import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.server.ktor.mappers.toChatEvent
import eu.torvian.chatbot.server.ktor.mappers.toChatStreamEvent
import eu.torvian.chatbot.server.ktor.routes.requireSessionAccess
import eu.torvian.chatbot.server.service.core.ChatService
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.MessageEvent
import eu.torvian.chatbot.server.service.core.MessageStreamEvent
import eu.torvian.chatbot.server.service.core.error.message.ProcessNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.core.error.message.toApiError
import eu.torvian.chatbot.server.service.core.toolcall.OperatorToolExecutionResult
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallApprovalSubmission
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Coordinates the live `/sessions/{sessionId}/messages` WebSocket protocol workflow.
 *
 * The handler owns transport-facing concerns for one authenticated chat socket: first-frame
 * validation, request validation, client approval normalization, chat-service invocation,
 * outbound event serialization, and session-scoped error handling.
 *
 * @property chatService Application service that validates and executes chat turns.
 * @property authorizationService Authorization service used to enforce session access.
 * @property json Shared JSON codec used for inbound and outbound protocol frames.
 */
class SessionMessagesWebSocketHandler(
    private val chatService: ChatService,
    private val authorizationService: AuthorizationService,
    private val json: Json
) {
    /** Logger kept under the historic route name so operational output stays familiar. */
    private val logger: Logger = LogManager.getLogger("SessionRoutes")

    /**
     * Runs the complete WebSocket session workflow for one authenticated chat connection.
     *
     * @param socket Live Ktor WebSocket session bound to the transport connection.
     * @param userId Authenticated user that owns the session workflow.
     * @param sessionId Session whose message-processing protocol is being served.
     */
    suspend fun handle(
        socket: DefaultWebSocketServerSession,
        userId: Long,
        sessionId: Long
    ) {
        socket.run {
            logger.info("WS open: sessionId=$sessionId, userId=$userId")

            var request: ProcessNewMessageRequest? = null
            try {
                val initialFrame = incoming.receive() as? Frame.Text
                if (initialFrame == null) {
                    close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "Invalid frame type for initial request"
                        )
                    )
                    return@run
                }

                val initialEvent = json.decodeFromString<ChatClientEvent>(initialFrame.readText())
                val processRequest = (initialEvent as? ChatClientEvent.ProcessNewMessage)?.request
                if (processRequest == null) {
                    close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "First message must be ProcessNewMessage"
                        )
                    )
                    return@run
                }
                request = processRequest

                val validationResult = either {
                    requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                    withError({ validateError: ValidateNewMessageError -> validateError.toApiError() }) {
                        chatService.validateProcessNewMessageRequest(
                            userId,
                            sessionId,
                            processRequest.content,
                            processRequest.parentMessageId,
                            processRequest.isStreaming
                        ).bind()
                    }
                }

                val (session, llmConfig) = validationResult.getOrElse { apiError ->
                    logger.error("Validation failed for session $sessionId: $apiError")
                    outgoing.send(Frame.Text(serializeErrorFrame(processRequest.isStreaming, apiError)))
                    close(CloseReason(CloseReason.Codes.NORMAL, "Validation failed"))
                    return@run
                }

                coroutineScope {
                    // Keep the inbound collector alive after Cancel so terminal cancellation events can be sent.
                    val controlSignal = TurnControlSignal()
                    val outerSessionScope = this
                    val clientEventFlow = createClientEventFlow(
                        onChannelClosed = {
                            logger.info("WebSocket channel closed by client for session $sessionId")
                            // A transport failure is different from a turn cancel: it tears down everything.
                            outerSessionScope.cancel(CancellationException("Client closed WebSocket"))
                        },
                        onCancellationRequested = {
                            // Only the active turn is cancellable through the application protocol.
                            controlSignal.cancel()
                        },
                        onPauseRequested = {
                            // Pause is soft: the active LLM/tool step must be allowed to finish.
                            controlSignal.pause()
                        }
                    )
                    val approvalResponseFlow = clientEventFlow.toApprovalSubmissionFlow()
                    val operatorToolResultFlow = clientEventFlow.toOperatorToolResultFlow()

                    // Collect on the outer session scope so cooperative cancellation cannot stop event mapping
                    // or WebSocket forwarding.
                    if (processRequest.isStreaming) {
                        processStreamingRequest(
                            userId = userId,
                            session = session,
                            llmConfig = llmConfig,
                            request = processRequest,
                            approvalResponseFlow = approvalResponseFlow,
                            operatorToolResultFlow = operatorToolResultFlow,
                            controlSignal = controlSignal
                        )
                    } else {
                        processNonStreamingRequest(
                            userId = userId,
                            session = session,
                            llmConfig = llmConfig,
                            request = processRequest,
                            approvalResponseFlow = approvalResponseFlow,
                            operatorToolResultFlow = operatorToolResultFlow,
                            controlSignal = controlSignal
                        )
                    }
                    close(CloseReason(CloseReason.Codes.NORMAL, "Turn completed"))
                }

            } catch (e: ClosedReceiveChannelException) {
                logger.debug("WebSocket client channel closed for session $sessionId: ${e.message}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.error("Error in WebSocket session for session $sessionId: ${e.message}", e)
                val internalApiError = apiError(CommonApiErrorCodes.INTERNAL, "An unexpected error occurred.")
                runCatching {
                    outgoing.send(Frame.Text(serializeErrorFrame(request?.isStreaming == true, internalApiError)))
                }.onFailure { sendError ->
                    logger.debug("Skipping internal error frame for session $sessionId: ${sendError.message}")
                }
            } finally {
                logger.info("WebSocket closed: sessionId=$sessionId}")
            }
        }
    }

    /**
     * Builds the shared inbound client-event stream for the live socket.
     *
     * A shared flow is required because both approval normalization branches must consume the
     * same incoming transport frames without racing each other. The [onChannelClosed] callback
     * is invoked when the underlying channel completes (client disconnect), allowing the caller
     * to cancel the processing scope.
     *
     * @receiver Live Ktor WebSocket session that owns the inbound channel.
     * @param onChannelClosed Callback invoked when the WebSocket channel closes.
     * @param onCancellationRequested Callback invoked for an application-level cancellation event.
     * @param onPauseRequested Callback invoked for an application-level pause event.
     * @return Shared flow of decoded client events sourced from text frames.
     */
    private fun DefaultWebSocketServerSession.createClientEventFlow(
        onChannelClosed: () -> Unit,
        onCancellationRequested: () -> Unit,
        onPauseRequested: () -> Unit
    ): Flow<ChatClientEvent> {
        return incoming.receiveAsFlow()
            .onCompletion {
                onChannelClosed()
            }
            .filterIsInstance<Frame.Text>()
            .map { frame -> json.decodeFromString<ChatClientEvent>(frame.readText()) }
            .onEach { event ->
                when (event) {
                    ChatClientEvent.Cancel -> {
                        // Cancel is a control event, not an approval; leave the shared stream alive for the drain.
                        onCancellationRequested()
                    }
                    ChatClientEvent.Pause -> onPauseRequested()
                    else -> Unit
                }
            }
            .filterNot { event -> event is ChatClientEvent.Cancel || event is ChatClientEvent.Pause }
            .shareIn(this, SharingStarted.Eagerly)
    }

    /**
     * Normalizes WebSocket approval variants into the server-facing approval submission model.
     *
     * @receiver Decoded client-event stream for one live chat socket.
     * @return Flow containing Local MCP, built-in worker, operator, and server built-in tool
     *         approval submissions.
     */
    private fun Flow<ChatClientEvent>.toApprovalSubmissionFlow(): Flow<ToolCallApprovalSubmission> {
        return merge(
            filterIsInstance<ChatClientEvent.LocalMcpToolCallApproval>()
                .map { event ->
                    ToolCallApprovalSubmission.LocalMcpSigned(
                        signedRequest = event.signedRequest
                    )
                },
            filterIsInstance<ChatClientEvent.BuiltInToolCallApproval>()
                .map { event ->
                    ToolCallApprovalSubmission.BuiltInSigned(
                        signedRequest = event.signedRequest
                    )
                },
            filterIsInstance<ChatClientEvent.OperatorToolCallApproval>()
                .map { event ->
                    ToolCallApprovalSubmission.OperatorToolApproval(
                        toolCallId = event.toolCallId,
                        approved = event.approved,
                        denialReason = event.denialReason
                    )
                },
            filterIsInstance<ChatClientEvent.ServerBuiltInToolCallApproval>()
                .map { event ->
                    ToolCallApprovalSubmission.ServerBuiltInApproval(
                        toolCallId = event.toolCallId,
                        approved = event.approved,
                        denialReason = event.denialReason
                    )
                }
        )
    }

    /**
     * Maps operator tool execution results onto their dedicated server-facing channel.
     *
     * The flow is derived from the **same** shared [ChatClientEvent] stream as
     * [toApprovalSubmissionFlow], so the two channels never compete for frames. A result is
     * deliberately not an approval: it is consumed only by the operator-tool executor of the
     * matching tool call.
     *
     * @receiver Decoded client-event stream for one live chat socket.
     * @return Flow of [OperatorToolExecutionResult] replies from the operator.
     */
    private fun Flow<ChatClientEvent>.toOperatorToolResultFlow(): Flow<OperatorToolExecutionResult> {
        return filterIsInstance<ChatClientEvent.ToolExecutionResult>()
            .map { event ->
                OperatorToolExecutionResult(
                    toolCallId = event.toolCallId,
                    output = event.output,
                    isError = event.isError,
                    errorMessage = event.errorMessage
                )
            }
    }

    /**
     * Executes the validated non-streaming chat workflow and serializes only non-streaming events.
     *
     * @receiver Live Ktor WebSocket session that will receive outbound protocol frames.
     * @param userId Authenticated user that owns the message-processing request.
     * @param session Validated session resolved during initial request validation.
     * @param llmConfig Validated LLM configuration resolved during initial request validation.
     * @param request Initial non-streaming request frame payload.
     * @param approvalResponseFlow Normalized approval submissions from subsequent client events.
     * @param operatorToolResultFlow Dedicated channel carrying operator tool execution results.
     * @param controlSignal Cooperative cancellation requested for this turn.
     */
    private suspend fun DefaultWebSocketServerSession.processNonStreamingRequest(
        userId: Long,
        session: ChatSession,
        llmConfig: LLMConfig,
        request: ProcessNewMessageRequest,
        approvalResponseFlow: Flow<ToolCallApprovalSubmission>,
        operatorToolResultFlow: Flow<OperatorToolExecutionResult>,
        controlSignal: TurnControlSignal
    ) {
        chatService.processNewMessage(
            userId = userId,
            session = session,
            llmConfig = llmConfig,
            content = request.content,
            parentMessageId = request.parentMessageId,
            fileReferences = request.fileReferences,
            toolApprovalFlow = approvalResponseFlow,
            operatorToolResultFlow = operatorToolResultFlow,
            controlSignal = controlSignal
        ).collect { eitherEvent ->
            eitherEvent.fold(
                ifLeft = { processError ->
                    outgoing.send(Frame.Text(serializeNonStreamingProcessErrorFrame(processError)))
                },
                ifRight = { event ->
                    outgoing.send(Frame.Text(serializeNonStreamingEventFrame(event)))
                }
            )
        }
    }

    /**
     * Executes the validated streaming chat workflow and serializes only streaming events.
     *
     * @receiver Live Ktor WebSocket session that will receive outbound protocol frames.
     * @param userId Authenticated user that owns the message-processing request.
     * @param session Validated session resolved during initial request validation.
     * @param llmConfig Validated LLM configuration resolved during initial request validation.
     * @param request Initial streaming request frame payload.
     * @param approvalResponseFlow Normalized approval submissions from subsequent client events.
     * @param operatorToolResultFlow Dedicated channel carrying operator tool execution results.
     * @param controlSignal Cooperative cancellation requested for this turn.
     */
    private suspend fun DefaultWebSocketServerSession.processStreamingRequest(
        userId: Long,
        session: ChatSession,
        llmConfig: LLMConfig,
        request: ProcessNewMessageRequest,
        approvalResponseFlow: Flow<ToolCallApprovalSubmission>,
        operatorToolResultFlow: Flow<OperatorToolExecutionResult>,
        controlSignal: TurnControlSignal
    ) {
        chatService.processNewMessageStreaming(
            userId = userId,
            session = session,
            llmConfig = llmConfig,
            content = request.content,
            parentMessageId = request.parentMessageId,
            fileReferences = request.fileReferences,
            toolApprovalFlow = approvalResponseFlow,
            operatorToolResultFlow = operatorToolResultFlow,
            controlSignal = controlSignal
        ).collect { eitherEvent ->
            eitherEvent.fold(
                ifLeft = { processError ->
                    outgoing.send(Frame.Text(serializeStreamingProcessErrorFrame(processError)))
                },
                ifRight = { event ->
                    outgoing.send(Frame.Text(serializeStreamingEventFrame(event)))
                }
            )
        }
    }

    /**
     * Serializes one protocol-level error event for the current streaming mode.
     *
     * @param isStreaming Whether the active chat protocol session is streaming.
     * @param apiError API error payload that must be wrapped in the public WebSocket event shape.
     * @return Serialized JSON frame payload.
     */
    private fun serializeErrorFrame(isStreaming: Boolean, apiError: ApiError): String {
        return if (isStreaming) {
            json.encodeToString(ChatStreamEvent.ErrorOccurred(apiError) as ChatStreamEvent)
        } else {
            json.encodeToString(ChatEvent.ErrorOccurred(apiError) as ChatEvent)
        }
    }

    /**
     * Serializes one non-streaming process error into its public WebSocket frame payload.
     *
     * @param processError Internal non-streaming process error emitted by the chat service.
     * @return Serialized JSON frame payload.
     */
    private fun serializeNonStreamingProcessErrorFrame(processError: ProcessNewMessageError): String {
        return json.encodeToString(ChatEvent.ErrorOccurred(processError.toApiError()) as ChatEvent)
    }

    /**
     * Serializes one streaming process error into its public WebSocket frame payload.
     *
     * @param processError Internal streaming process error emitted by the chat service.
     * @return Serialized JSON frame payload.
     */
    private fun serializeStreamingProcessErrorFrame(processError: ProcessNewMessageError): String {
        return json.encodeToString(ChatStreamEvent.ErrorOccurred(processError.toApiError()) as ChatStreamEvent)
    }

    /**
     * Serializes one non-streaming chat event into its public WebSocket frame payload.
     *
     * @param event Internal non-streaming message event emitted by the chat service.
     * @return Serialized JSON frame payload.
     */
    private fun serializeNonStreamingEventFrame(event: MessageEvent): String {
        return json.encodeToString(event.toChatEvent())
    }

    /**
     * Serializes one streaming chat event into its public WebSocket frame payload.
     *
     * @param event Internal streaming message event emitted by the chat service.
     * @return Serialized JSON frame payload.
     */
    private fun serializeStreamingEventFrame(event: MessageStreamEvent): String {
        return json.encodeToString(event.toChatStreamEvent())
    }
}