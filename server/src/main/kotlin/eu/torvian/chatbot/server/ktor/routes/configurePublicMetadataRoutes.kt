package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.ServerInfo
import eu.torvian.chatbot.common.models.api.metadata.ServerInfoResponse
import eu.torvian.chatbot.server.VersionInfo
import eu.torvian.chatbot.server.main.ServerControlService
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

/**
 * Public metadata routes (no authentication required).
 *
 * @param serverControl The server control service for obtaining runtime server information.
 */
fun Route.configurePublicMetadataRoutes(serverControl: ServerControlService) {
    get<ServerInfo> {
        // Obtain runtime server info - should never be null during active request
        val serverInstance = checkNotNull(serverControl.getServerInfo()) {
            "Internal Server Error: ServerInstanceInfo is missing during active request."
        }

        val response = ServerInfoResponse(
            appName = "Torvian Chatbot",
            version = VersionInfo.VERSION,
            startTime = serverInstance.startTime
        )

        call.respond(response)
    }
}
