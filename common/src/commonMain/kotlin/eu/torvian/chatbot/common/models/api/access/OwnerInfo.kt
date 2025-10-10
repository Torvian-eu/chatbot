package eu.torvian.chatbot.common.models.api.access

import kotlinx.serialization.Serializable

/**
 * Information about the owner of a resource.
 *
 * @property userId The unique identifier of the owner
 * @property username The username of the owner
 */
@Serializable
data class OwnerInfo(
    val userId: Long,
    val username: String
)