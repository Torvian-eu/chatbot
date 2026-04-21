package eu.torvian.chatbot.worker.protocol.transport

import eu.torvian.chatbot.worker.protocol.handshake.HelloStartResult
import eu.torvian.chatbot.worker.protocol.handshake.HelloStarter
import eu.torvian.chatbot.worker.protocol.routing.IncomingMessageProcessor
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Runs one authenticated worker WebSocket session lifecycle.
 *
 * @property client Ktor client configured with WebSockets support.
 * @property transportConfig Immutable settings for endpoint and hello payload metadata.
 * @property codec Text-frame codec for worker protocol envelopes.
 * @property outboundEmitterHolder Mutable outbound bridge bound to the active session sender.
 * @property helloStarter Hello interaction launcher invoked after transport binding.
 * @property incomingMessageProcessor Processor that handles decoded inbound envelopes.
 */
class WebSocketSessionRunner(
    private val client: HttpClient,
    private val transportConfig: WebSocketTransportConfig,
    private val codec: WebSocketMessageCodec,
    private val outboundEmitterHolder: OutboundMessageEmitterHolder,
    private val helloStarter: HelloStarter,
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
                outboundEmitterHolder.bind { message ->
                    send(Frame.Text(codec.encode(message)))
                }

                logger.info("Worker WebSocket connected")
                when (
                    val helloResult = helloStarter.start(
                        workerUid = transportConfig.workerUid,
                        capabilities = transportConfig.capabilities,
                        supportedProtocolVersions = transportConfig.supportedProtocolVersions,
                        workerVersion = transportConfig.workerVersion
                    )
                ) {
                    is HelloStartResult.Started -> {
                        logger.info("Started worker session hello interaction (interactionId={})", helloResult.interactionId)
                    }

                    is HelloStartResult.NotStarted -> {
                        logger.warn(
                            "Failed to start worker session hello interaction (interactionId={}, reason={})",
                            helloResult.interactionId,
                            helloResult.reason
                        )
                    }
                }

                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
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

                        // Ping/Pong/Binary frames are not part of the first transport iteration.
                        else -> Unit
                    }
                }

                logger.warn("Worker WebSocket closed by peer")
            }

            WebSocketSessionResult(stableConnection = true, authRejected = false)
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

    companion object {
        /**
         * Logger used for worker transport lifecycle diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(WebSocketSessionRunner::class.java)
    }
}



