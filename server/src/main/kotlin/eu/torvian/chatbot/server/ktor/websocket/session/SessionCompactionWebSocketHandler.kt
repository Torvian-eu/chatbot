package eu.torvian.chatbot.server.ktor.websocket.session

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.models.api.core.CompactionEvent
import eu.torvian.chatbot.server.ktor.mappers.toCompactionEvent
import eu.torvian.chatbot.server.ktor.routes.requireSessionAccess
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationManualCompactionService
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Coordinates the live `/sessions/{sessionId}/compaction` WebSocket protocol workflow.
 *
 * Connecting **is** the request: there is no request frame to receive and no client event to
 * interpret, so the handler runs one forced compaction for the authenticated session owner as soon as
 * the socket is established. It answers with exactly one terminal outcome frame (a persisted chunk, a
 * no-op, or a failure) followed by [CompactionEvent.StreamCompleted], and then closes normally. An
 * access denial and an unexpected server error follow the same sequence, so a client may always wait
 * for the terminal marker before settling.
 *
 * Cancellation and a transport drop are the same server-side action: closing the socket is observed by
 * draining the inbound channel, which cancels the running compaction, so the auxiliary call is aborted
 * and nothing beyond an already-committed chunk is persisted.
 *
 * @property manualCompactionService Service that performs the forced compaction.
 * @property authorizationService Authorization service used to enforce session access.
 * @property json Shared JSON codec used for outbound protocol frames.
 */
class SessionCompactionWebSocketHandler(
    private val manualCompactionService: ConversationManualCompactionService,
    private val authorizationService: AuthorizationService,
    private val json: Json
) {
    /** Logger kept under the historic route name so operational output stays familiar. */
    private val logger: Logger = LogManager.getLogger("SessionRoutes")

    /**
     * Runs the complete WebSocket workflow for one authenticated compaction connection.
     *
     * @param socket Live Ktor WebSocket session bound to the transport connection.
     * @param userId Authenticated user that owns the session workflow.
     * @param sessionId Session whose thread is compacted.
     */
    suspend fun handle(
        socket: DefaultWebSocketServerSession,
        userId: Long,
        sessionId: Long
    ) {
        socket.run {
            logger.info("Compaction WS open: sessionId=$sessionId, userId=$userId")
            try {
                val accessFailure = either {
                    requireSessionAccess(authorizationService, userId, sessionId, AccessMode.WRITE)
                }.leftOrNull()
                if (accessFailure != null) {
                    logger.error("Compaction access denied for session $sessionId: $accessFailure")
                    sendOutcomeFrames(
                        listOf(CompactionEvent.ErrorOccurred(accessFailure), CompactionEvent.StreamCompleted),
                        sessionId
                    )
                    close(CloseReason(CloseReason.Codes.NORMAL, "Access denied"))
                    return@run
                }

                // Exactly one terminal outcome: the compacted chunk, the no-op reason, or the failure.
                // The compaction runs in its own scope so that the peer leaving can cancel it: a client
                // close does not cancel this handler by itself.
                val outcomeEvent = coroutineScope {
                    val compactionScope = this
                    val clientWatcher = launch { watchForClientClose(compactionScope, sessionId) }
                    try {
                        either {
                            withError({ error: ConversationCompactionError -> error.toCompactionEvent() }) {
                                manualCompactionService.compactThread(userId, sessionId).bind()
                            }
                        }.fold(
                            ifLeft = { it },
                            ifRight = { it.toCompactionEvent() }
                        )
                    } finally {
                        // The outcome is decided, so the watch must not keep the handler alive on a socket
                        // that stays open. Cancelling an already cancelled watcher is a no-op.
                        clientWatcher.cancel()
                    }
                }

                logger.info(
                    "Compaction WS result for session {}: {}",
                    sessionId,
                    outcomeEvent::class.simpleName
                )
                sendOutcomeFrames(listOf(outcomeEvent, CompactionEvent.StreamCompleted), sessionId)
                close(CloseReason(CloseReason.Codes.NORMAL, "Compaction finished"))
            } catch (e: ClosedReceiveChannelException) {
                logger.debug("WebSocket client channel closed for session $sessionId: ${e.message}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.error("Error in compaction WebSocket for session $sessionId: ${e.message}", e)
                val internalApiError = apiError(CommonApiErrorCodes.INTERNAL, "An unexpected error occurred.")
                sendOutcomeFrames(
                    listOf(CompactionEvent.ErrorOccurred(internalApiError), CompactionEvent.StreamCompleted),
                    sessionId
                )
            } finally {
                logger.info("Compaction WS closed: sessionId=$sessionId")
            }
        }
    }

    /**
     * Waits until the connected peer goes away and cancels the running compaction, which is the only signal
     * of a client close: no frame carries meaning on this socket, and Ktor does not cancel a handler
     * coroutine when its peer closes.
     *
     * @receiver Live Ktor WebSocket session whose inbound channel signals the connection lifetime.
     * @param compactionScope Scope running the compaction; cancelled once the peer is gone.
     * @param sessionId Session the socket belongs to (log context only).
     */
    private suspend fun DefaultWebSocketServerSession.watchForClientClose(
        compactionScope: CoroutineScope,
        sessionId: Long
    ) {
        // A closed channel ends the loop; cancelling this watcher instead throws out of `receiveCatching`,
        // so a normal completion never reaches the cancellation below.
        while (true) {
            val frame = incoming.receiveCatching().getOrNull() ?: break
            logger.debug(
                "Ignoring inbound {} frame on session {} for the compaction socket",
                frame.frameType,
                sessionId
            )
        }
        logger.info("Compaction WS channel closed by the client for session $sessionId")
        compactionScope.cancel(CancellationException("Client closed the compaction socket"))
    }

    /**
     * Serializes the outcome frames before sending any of them and tolerates a peer that left mid-write.
     *
     * Encoding happens outside the guarded send so a serialization defect still surfaces as a server
     * error, while a vanished peer (which Ktor reports as a closed channel) is only logged: the
     * operation's outcome is already committed and needs no retry.
     *
     * @param events Terminal events to deliver, in order.
     * @param sessionId Session the frames belong to (log context only).
     */
    private suspend fun DefaultWebSocketServerSession.sendOutcomeFrames(
        events: List<CompactionEvent>,
        sessionId: Long
    ) {
        val frames = events.map { event -> Frame.Text(serialize(event)) }
        try {
            frames.forEach { frame -> outgoing.send(frame) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (peerGone: Exception) {
            logger.debug(
                "Compaction outcome for session {} could not be delivered: {}",
                sessionId,
                peerGone.message
            )
        }
    }

    /**
     * Serializes one compaction event into its public frame payload.
     *
     * @param event Event to serialize.
     * @return Serialized JSON frame payload.
     */
    private fun serialize(event: CompactionEvent): String = json.encodeToString<CompactionEvent>(event)
}
