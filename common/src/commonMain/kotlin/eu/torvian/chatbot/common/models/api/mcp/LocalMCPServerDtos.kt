package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable

/**
 * Request DTO for creating a new Local MCP Server.
 *
 * The userId is extracted from the JWT on the server side, so it's not included here.
 *
 * @property isEnabled The initial enabled/disabled state
 */
@Serializable
data class CreateServerRequest(
    val isEnabled: Boolean
)

/**
 * Response DTO for creating a new Local MCP Server.
 *
 * @property id The generated server ID
 * @property userId The ID of the user who owns this server
 * @property isEnabled The enabled/disabled state
 */
@Serializable
data class CreateServerResponse(
    val id: Long,
    val userId: Long,
    val isEnabled: Boolean
)

/**
 * Request DTO for updating the enabled state of a Local MCP Server.
 *
 * @property isEnabled The new enabled/disabled state
 */
@Serializable
data class SetServerEnabledRequest(
    val isEnabled: Boolean
)

/**
 * Response DTO for listing server IDs.
 *
 * @property ids List of server IDs owned by the user
 * @property userId The ID of the user
 */
@Serializable
data class ServerIdsResponse(
    val ids: List<Long>,
    val userId: Long
)
