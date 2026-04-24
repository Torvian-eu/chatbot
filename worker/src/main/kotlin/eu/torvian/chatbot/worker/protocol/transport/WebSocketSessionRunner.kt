package eu.torvian.chatbot.worker.protocol.transport

import eu.torvian.chatbot.worker.protocol.handshake.HelloStartResult
import eu.torvian.chatbot.worker.protocol.handshake.HelloStarter
import eu.torvian.chatbot.worker.protocol.handshake.SessionHandshakeContext
import eu.torvian.chatbot.worker.protocol.handshake.SessionHandshakeState
import eu.torvian.chatbot.worker.protocol.routing.IncomingMessageProcessor
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Runs one authenticated worker WebSocket session lifecycle.
 *
 * @property client HTTP client used to open the WebSocket connection.
 * @property transportConfig Transport configuration used to obtain the WebSocket URL and worker metadata.
 * @property codec Codec used to encode and decode worker protocol messages.
 * @property outboundEmitterHolder Holder for the outbound message emitter that binds the transport to the protocol layer.
 * @property helloStarter Starter for the worker-side hello handshake interaction.
 * @property handshakeContext Session-scoped handshake recorder used to track handshake outcome.
 * @property incomingMessageProcessor Processor for inbound worker protocol messages received over the WebSocket.
 */
class WebSocketSessionRunner(
    private val client: HttpClient,
    private val transportConfig: WebSocketTransportConfig,
    private val codec: WebSocketMessageCodec,
    private val outboundEmitterHolder: OutboundMessageEmitterHolder,
    private val helloStarter: HelloStarter,
    private val handshakeContext: SessionHandshakeContext,
    private val incomingMessageProcessor: IncomingMessageProcessor
) {
    /**
     * Opens one authenticated socket, binds outbound transport, starts hello, and processes inbound frames.
     *
     * @param accessToken Bearer token used for the WebSocket handshake.
     * @return Session outcome used by the reconnect loop.
     */
    suspend fun run(accessToken: String): WebSocketSessionResult {
        logger.info("Opening worker WebSocket connection to {}", transportConfig.webSocketUrl)
        return try {
            client.webSocket({
                url(transportConfig.webSocketUrl)
                bearerAuth(accessToken)
            }) {
                logger.info("Worker WebSocket session opened, starting handshake")
                handshakeContext.reset()

                logger.debug("WebSocket transport binding: setting up outbound message emitter")
                outboundEmitterHolder.bind { message ->
                    logger.debug("Sending outbound worker protocol message (type={})", message.type)
                    send(Frame.Text(codec.encode(message)))
                }

                logger.debug("Starting worker session hello interaction")
                when (
                    val helloResult = helloStarter.start(
                        workerUid = transportConfig.workerUid,
                        capabilities = transportConfig.capabilities,
                        supportedProtocolVersions = transportConfig.supportedProtocolVersions,
                        workerVersion = transportConfig.workerVersion
                    )
                ) {
                    is HelloStartResult.Started -> runStartedSession(this, helloResult)
                    is HelloStartResult.NotStarted -> {
                        logger.warn(
                            "Failed to start worker session hello interaction (interactionId={}, reason={})",
                            helloResult.interactionId,
                            helloResult.reason
                        )
                        close()
                        return@webSocket
                    }
                }

                logger.warn("Worker WebSocket closed by peer")
            }

            WebSocketSessionResult(stableConnection = handshakeContext.hasSucceeded(), authRejected = false)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResponseException) {
            val authRejected = error.response.status == HttpStatusCode.Unauthorized ||
                    error.response.status == HttpStatusCode.Forbidden
            logger.warn("Worker WebSocket handshake failed (status={})", error.response.status)
            WebSocketSessionResult(stableConnection = false, authRejected = authRejected)
        } catch (error: Exception) {
            logger.warn("Worker WebSocket session failed", error)
            WebSocketSessionResult(stableConnection = false, authRejected = false)
        } finally {
            outboundEmitterHolder.unbind()
        }
    }

    /**
     * Runs the started hello interaction and processes inbound frames while monitoring handshake completion.
     *
     * @param session Active websocket session used for inbound frame iteration and close signaling.
     * @param helloResult Successful hello start result that exposes the terminal handshake state.
     */
    private suspend fun runStartedSession(session: WebSocketSession, helloResult: HelloStartResult.Started) {
        logger.info("Started worker session hello interaction (interactionId={})", helloResult.interactionId)
        coroutineScope {
            val handshakeMonitor = launch {
                monitorHandshake(session)
            }

            try {
                processInboundFrames(session)
            } finally {
                handshakeMonitor.cancel()
            }
        }
    }

    /**
     * Waits for the handshake to reach a terminal state and reacts to success or failure.
     *
     * @param session Active websocket session used to close the connection on handshake failure.
     */
    private suspend fun monitorHandshake(session: WebSocketSession) {
        try {
            when (
                val handshakeState = withTimeout(transportConfig.helloWelcomeTimeoutMs) {
                    handshakeContext.awaitTerminalState()
                }
            ) {
                is SessionHandshakeState.Failed -> {
                    logger.warn("Handshake failed; closing worker WebSocket session (reason={})", handshakeState.reason)
                    session.close()
                }

                is SessionHandshakeState.Succeeded -> {
                    logger.info(
                        "Handshake succeeded; worker session is ready for work (workerUid={}, selectedProtocolVersion={}, acceptedCapabilities={})",
                        handshakeState.welcome.workerUid,
                        handshakeState.welcome.selectedProtocolVersion,
                        handshakeState.welcome.acceptedCapabilities
                    )
                }

                SessionHandshakeState.Pending -> {
                    logger.warn("Handshake monitor observed pending state, which should be impossible after awaiting terminal state")
                }
            }
        } catch (_: TimeoutCancellationException) {
            val reason = "Timed out waiting for session.welcome after ${transportConfig.helloWelcomeTimeoutMs}ms"
            logger.warn(reason)
            handshakeContext.markFailed(reason)
            session.close()
        }
    }

    /**
     * Processes inbound frames until the socket closes.
     *
     * Text frames are decoded and routed; other frame types are ignored.
     *
     * @param session Active websocket session that provides inbound frames.
     */
    private suspend fun processInboundFrames(session: WebSocketSession) {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> processInboundTextFrame(frame)
                else -> Unit
            }
        }
    }

    /**
     * Decodes and routes one inbound text frame.
     *
     * @param frame Text frame containing a worker protocol envelope.
     */
    private suspend fun processInboundTextFrame(frame: Frame.Text) {
        val decoded = codec.decode(frame.readText())
        decoded.fold(
            ifLeft = { error ->
                logger.warn("Failed to decode inbound worker protocol frame: {}", error)
            },
            ifRight = { message ->
                try {
                    incomingMessageProcessor.process(message)
                } catch (error: Exception) {
                    logger.error(
                        "Inbound worker protocol processing failed (id={}, type={})",
                        message.id,
                        message.type,
                        error
                    )
                }
            }
        )
    }

    companion object {
        /**
         * Logger used for worker transport lifecycle diagnostics.
         *
         * Logs socket opening/closing, frame transmission, hello handshake progress,
         * inbound message processing, and transport errors.
         */
        private val logger: Logger = LogManager.getLogger(WebSocketSessionRunner::class.java)
    }
}
