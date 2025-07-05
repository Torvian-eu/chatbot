package eu.torvian.chatbot.server.main

import kotlinx.datetime.Instant

/**
 * Data class to hold server instance information.
 *
 * @property scheme The scheme to use for the server (e.g., "http" or "https").
 * @property host The host the server is bound to.
 * @property port The actual port the server is bound to.
 * @property path The base path for the server.
 * @property startTime The time when the server was started.
 */
data class ServerInstanceInfo(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val startTime: Instant
) {
    /**
     * The base URI for the server. (e.g., http://localhost:8080/api/v1)
     */
    val baseUri: String
        get() = "$scheme://$host:$port$path"
}