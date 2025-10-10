package eu.torvian.chatbot.common.models.api.access

import kotlinx.serialization.Serializable

/**
 * Request to revoke access to a resource from a user group.
 *
 * @property groupId The ID of the user group to revoke access from
 * @property accessMode The access mode to revoke (e.g., "read", "write")
 */
@Serializable
data class RevokeAccessRequest(
    val groupId: Long,
    val accessMode: String
)