package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.WsResource
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getWorkerContext
import eu.torvian.chatbot.server.worker.command.pending.PendingWorkerCommandRegistry
import eu.torvian.chatbot.server.worker.protocol.routing.WorkerServerIncomingMessageRouter
import eu.torvian.chatbot.server.worker.protocol.codec.WorkerServerWebSocketMessageCodec
import eu.torvian.chatbot.server.worker.session.ConnectedWorkerSession
import eu.torvian.chatbot.server.worker.session.WorkerSessionRegistry
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Configures the worker WebSocket route family.
 *
 * This route is intentionally separate from the worker registration REST routes because it owns
 * the long-lived transport/session lifecycle for authenticated worker connections.
 *
 * @param workerSessionRegistry Registry that tracks currently connected worker sessions.
 * @param messageCodec Shared worker protocol codec used for outbound serialization in the live session.
 * @param messageRouter Inbound protocol router used to dispatch decoded envelopes.
 * @param pendingCommandRegistry Registry used to fail pending command interactions on disconnect.
 */
@Suppress("unused")
fun Route.configureWorkerWebSocketRoutes(
    workerSessionRegistry: WorkerSessionRegistry,
    messageCodec: WorkerServerWebSocketMessageCodec,
    messageRouter: WorkerServerIncomingMessageRouter,
    pendingCommandRegistry: PendingWorkerCommandRegistry
) {
    authenticate(AuthSchemes.WORKER_JWT) {
        webSocket<WsResource.Workers.Connect> { _ ->
            val workerContext = call.getWorkerContext()
            logger.info(
                "Worker WebSocket connection accepted (workerId={}, workerUid={}, ownerUserId={})",
                workerContext.workerId,
                workerContext.workerUid,
                workerContext.ownerUserId
            )

            ConnectedWorkerSession(
                socket = this,
                workerContext = workerContext,
                codec = messageCodec,
                router = messageRouter,
                registry = workerSessionRegistry,
                pendingCommandRegistry = pendingCommandRegistry
            ).run()
        }
    }
}

private val logger: Logger = LogManager.getLogger("WorkerWebSocketRoutes")


