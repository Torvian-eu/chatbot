package eu.torvian.chatbot.common.models.api.access

import kotlinx.serialization.Serializable

/**
 * Request to grant access to a resource for a user group.
 *
 * @property groupId The ID of the user group to grant access to
 * @property accessMode The access mode to grant (e.g., "read", "write")
 */
@Serializable
data class GrantAccessRequest(
    val groupId: Long,
    val accessMode: String
)