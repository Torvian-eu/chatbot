package eu.torvian.chatbot.server.ktor.routes

import io.ktor.resources.*
import io.ktor.server.resources.*
import io.ktor.server.resources.Resources
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.serializer

/**
 * Registers a typed WebSocket handler for a Ktor [Resource] class [T].
 *
 * This function combines the type-safe routing of the Resources plugin with WebSockets,
 * providing a deserialized instance of [T] (from path and query parameters) to the handler.
 * Requires both the [Resources] and [WebSockets] plugins to be installed.
 *
 * Usage: `webSocket<MyResource> { resource -> ... }`
 *
 * @param T The `@Resource` annotated class.
 * @param protocol Optional websocket subprotocol selected by the server during handshake.
 * @param handler The block for the WebSocket session. Receives the [DefaultWebSocketServerSession]
 *                as its context and the typed [resource] instance as a parameter.
 * @return The created [Route] for the resource.
 */
inline fun <reified T : Any> Route.webSocket(
    protocol: String? = null,
    crossinline handler: suspend DefaultWebSocketServerSession.(T) -> Unit
): Route =
    // Register a typed resource route
    resource<T> {
        // Register a WebSocket route within the resource route
        webSocket(protocol = protocol) {
            // Deserialize the resource from the call parameters
            val resources = plugin(Resources)
            val resource = resources.resourcesFormat.decodeFromParameters(serializer<T>(), call.parameters)

            // Call the handler with the deserialized resource
            handler(resource)
        }
    }