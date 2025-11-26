package eu.torvian.chatbot.common.models.api.mcp

import kotlinx.serialization.Serializable

/**
 * Response DTO for generating a new Local MCP Server ID.
 *
 * @property id The generated server ID
 * @property userId The ID of the user who owns this server
 */
@Serializable
data class GenerateServerIdResponse(
    val id: Long,
    val userId: Long
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
