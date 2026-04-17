package eu.torvian.chatbot.server.worker.session

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolVersion
import eu.torvian.chatbot.server.domain.security.WorkerContext
import eu.torvian.chatbot.server.worker.command.pending.PendingWorkerCommandRegistry
import eu.torvian.chatbot.server.worker.protocol.codec.WorkerServerWebSocketMessageCodec
import eu.torvian.chatbot.server.worker.protocol.routing.WorkerServerIncomingMessageRouter
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Represents one authenticated live worker socket connection.
 *
 * The session owns the socket read loop, outbound transport helper, and lifecycle state so the
 * route layer can stay thin and future command dispatch can target a single abstraction.
 *
 * @property socket Ktor WebSocket session for the live connection.
 * @property workerContext Authenticated worker principal resolved during the HTTP upgrade.
 * @property codec Shared server-side worker protocol codec.
 * @property router Inbound protocol router used to dispatch decoded envelopes.
 * @property registry In-memory registry used to unregister this session on disconnect.
 * @property pendingCommandRegistry Registry used to fail in-flight commands when the socket drops.
 */
class ConnectedWorkerSession(
    private val socket: DefaultWebSocketServerSession,
    val workerContext: WorkerContext,
    private val codec: WorkerServerWebSocketMessageCodec,
    private val router: WorkerServerIncomingMessageRouter,
    private val registry: WorkerSessionRegistry,
    private val pendingCommandRegistry: PendingWorkerCommandRegistry
) {
    private val sendMutex = Mutex()

    @Volatile
    private var currentState: WorkerSessionState = WorkerSessionState.Connected

    /**
     * Indicates whether the connection completed hello/welcome negotiation.
     */
    fun isReady(): Boolean = currentState is WorkerSessionState.Ready

    /**
     * Returns negotiated ready metadata when the handshake already completed.
     *
     * @return Ready state snapshot, or `null` when the session is still pre-handshake or closed.
     */
    fun readyState(): WorkerSessionState.Ready? = currentState as? WorkerSessionState.Ready

    /**
     * Marks the session as ready for command dispatch with negotiated handshake metadata.
     *
     * @param selectedProtocolVersion Protocol version negotiated for this socket.
     * @param acceptedCapabilities Normalized capabilities accepted from the worker hello payload.
     */
    fun markReady(selectedProtocolVersion: Int, acceptedCapabilities: List<String>) {
        currentState = WorkerSessionState.Ready(
            selectedProtocolVersion = selectedProtocolVersion,
            acceptedCapabilities = acceptedCapabilities
        )
    }

    /**
     * Sends one worker protocol envelope over the live socket.
     *
     * The send path is serialized because future command dispatch may emit from multiple
     * coroutines, and interleaved text frames would corrupt the protocol stream.
     *
     * @param message Worker protocol envelope to transmit.
     * @return `true` when the frame was written successfully; otherwise `false`.
     */
    suspend fun send(message: WorkerProtocolMessage): Boolean {
        sendMutex.withLock {
            if (currentState is WorkerSessionState.Closed) {
                logger.debug(
                    "Skipping outbound worker protocol frame because the session is already closed (workerId={}, type={}, messageId={})",
                    workerContext.workerId,
                    message.type,
                    message.id
                )
                return false
            }

            return try {
                socket.send(Frame.Text(codec.encode(message)))
                true
            } catch (exception: Exception) {
                if (exception is CancellationException) {
                    throw exception
                }
                logger.warn(
                    "Failed to write outbound worker protocol frame (workerId={}, type={}, messageId={})",
                    workerContext.workerId,
                    message.type,
                    message.id,
                    exception
                )
                false
            }
        }
    }

    /**
     * Closes the live socket and transitions the session into a terminal state.
     *
     * @param reason Optional close reason exposed to the peer.
     */
    suspend fun close(reason: CloseReason? = null) {
        currentState = WorkerSessionState.Closed(reason?.message)
        if (reason == null) {
            socket.close()
        } else {
            socket.close(reason)
        }
    }

    /**
     * Runs the receive loop until the peer disconnects or a protocol violation occurs.
     */
    suspend fun run() {
        logger.info(
            "Worker session started (workerId={}, workerUid={}, ownerUserId={})",
            workerContext.workerId,
            workerContext.workerUid,
            workerContext.ownerUserId
        )
        try {
            for (frame in socket.incoming) {
                when (frame) {
                    is Frame.Text -> handleTextFrame(frame.readText())
                    else -> closeProtocolViolation("Unsupported non-text worker WebSocket frame")
                }
            }
        } catch (exception: Exception) {
            if (exception is kotlinx.coroutines.CancellationException) {
                throw exception
            }
            logger.warn(
                "Worker session failed unexpectedly (workerId={})",
                workerContext.workerId,
                exception
            )
        } finally {
            cleanupAfterDisconnect()
        }
    }

    private suspend fun handleTextFrame(text: String) {
        when (val decoded = codec.decode(text)) {
            is Either.Left -> {
                logger.warn(
                    "Failed to decode worker protocol frame (workerId={}, error={})",
                    workerContext.workerId,
                    decoded.value
                )
                closeProtocolViolation("Malformed worker protocol frame")
            }

            is Either.Right -> {
                val message = decoded.value
                if (message.protocolVersion !in WorkerProtocolVersion.SUPPORTED) {
                    logger.warn(
                        "Rejected worker protocol frame with unsupported version (workerId={}, version={})",
                        workerContext.workerId,
                        message.protocolVersion
                    )
                    closeProtocolViolation("Unsupported worker protocol version")
                    return
                }

                router.route(this, message)
            }
        }
    }

    /**
     * Closes the socket with a policy-violation reason.
     *
     * @param message Human-readable explanation for the remote peer.
     */
    suspend fun closeProtocolViolation(message: String) {
        if (currentState is WorkerSessionState.Closed) {
            return
        }

        close(
            CloseReason(
                CloseReason.Codes.VIOLATED_POLICY,
                message
            )
        )
    }

    /**
     * Finalizes local session state and unregisters the socket from the live session registry.
     */
    private fun cleanupAfterDisconnect() {
        val negotiatedReadyState = readyState()
        currentState = WorkerSessionState.Closed("socket closed")
        val failedCommands = pendingCommandRegistry.failAllForWorker(
            workerId = workerContext.workerId,
            reason = "Worker session disconnected"
        )
        registry.remove(workerContext.workerId, this)
        logger.info(
            "Worker session closed (workerId={}, workerUid={}, ready={}, negotiatedProtocolVersion={}, acceptedCapabilities={}, failedPendingCommands={})",
            workerContext.workerId,
            workerContext.workerUid,
            negotiatedReadyState != null,
            negotiatedReadyState?.selectedProtocolVersion,
            negotiatedReadyState?.acceptedCapabilities,
            failedCommands
        )
    }

    companion object {
        /**
         * Logger used for worker session lifecycle diagnostics.
         */
        private val logger: Logger = LogManager.getLogger(ConnectedWorkerSession::class.java)
    }
}





