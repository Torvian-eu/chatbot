package eu.torvian.chatbot.common.models
import kotlinx.serialization.Serializable
/**
 * Request body for updating the group ID of a chat session.
 *
 * @property groupId The new optional group ID for the session. Null means ungrouped.
 */
@Serializable
data class UpdateSessionGroupRequest(
    val groupId: Long?
)