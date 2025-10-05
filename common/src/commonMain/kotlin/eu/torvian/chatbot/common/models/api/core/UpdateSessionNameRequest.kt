package eu.torvian.chatbot.common.models.api.core
import kotlinx.serialization.Serializable
/**
 * Request body for updating the name of a chat session.
 *
 * @property name The new name for the session.
 */
@Serializable
data class UpdateSessionNameRequest(
    val name: String
)