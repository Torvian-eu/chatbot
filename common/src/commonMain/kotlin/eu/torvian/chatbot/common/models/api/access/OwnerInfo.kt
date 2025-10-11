package eu.torvian.chatbot.common.models.api.access

import eu.torvian.chatbot.common.models.user.User
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

/**
 * Converts a [User] to an [OwnerInfo] by extracting relevant fields.
 */
fun User.toOwnerInfo(): OwnerInfo = OwnerInfo(id, username)
