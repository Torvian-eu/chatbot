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
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallApprovalSubmission
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
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

                val clientEventFlow = createClientEventFlow()
                val approvalResponseFlow = clientEventFlow.toApprovalSubmissionFlow()

                if (processRequest.isStreaming) {
                    processStreamingRequest(
                        userId = userId,
                        session = session,
                        llmConfig = llmConfig,
                        request = processRequest,
                        approvalResponseFlow = approvalResponseFlow
                    )
                } else {
                    processNonStreamingRequest(
                        userId = userId,
                        session = session,
                        llmConfig = llmConfig,
                        request = processRequest,
                        approvalResponseFlow = approvalResponseFlow
                    )
                }
            } catch (e: ClosedReceiveChannelException) {
                logger.debug("WebSocket client channel closed for session $sessionId: ${e.message}")
            } catch (e: Exception) {
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
     * same incoming transport frames without racing each other.
     *
     * @receiver Live Ktor WebSocket session that owns the inbound channel.
     * @return Shared flow of decoded client events sourced from text frames.
     */
    private fun DefaultWebSocketServerSession.createClientEventFlow(): Flow<ChatClientEvent> {
        return incoming.receiveAsFlow()
            .filterIsInstance<Frame.Text>()
            .map { frame -> json.decodeFromString<ChatClientEvent>(frame.readText()) }
            .shareIn(this, SharingStarted.Eagerly)
    }

    /**
     * Normalizes WebSocket approval variants into the server-facing approval submission model.
     *
     * @receiver Decoded client-event stream for one live chat socket.
     * @return Flow containing both regular and Local MCP approval submissions.
     */
    private fun Flow<ChatClientEvent>.toApprovalSubmissionFlow(): Flow<ToolCallApprovalSubmission> {
        return merge(
            filterIsInstance<ChatClientEvent.ToolCallApproval>()
                .map { event -> ToolCallApprovalSubmission.Standard(event.response) },
            filterIsInstance<ChatClientEvent.LocalMcpToolCallApproval>()
                .map { event ->
                    ToolCallApprovalSubmission.LocalMcpSigned(
                        signedRequest = event.signedRequest
                    )
                }
        )
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
     */
    private suspend fun DefaultWebSocketServerSession.processNonStreamingRequest(
        userId: Long,
        session: ChatSession,
        llmConfig: LLMConfig,
        request: ProcessNewMessageRequest,
        approvalResponseFlow: Flow<ToolCallApprovalSubmission>
    ) {
        chatService.processNewMessage(
            userId = userId,
            session = session,
            llmConfig = llmConfig,
            content = request.content,
            parentMessageId = request.parentMessageId,
            fileReferences = request.fileReferences,
            toolApprovalFlow = approvalResponseFlow
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
     */
    private suspend fun DefaultWebSocketServerSession.processStreamingRequest(
        userId: Long,
        session: ChatSession,
        llmConfig: LLMConfig,
        request: ProcessNewMessageRequest,
        approvalResponseFlow: Flow<ToolCallApprovalSubmission>
    ) {
        chatService.processNewMessageStreaming(
            userId = userId,
            session = session,
            llmConfig = llmConfig,
            content = request.content,
            parentMessageId = request.parentMessageId,
            fileReferences = request.fileReferences,
            toolApprovalFlow = approvalResponseFlow
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