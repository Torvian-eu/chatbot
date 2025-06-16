package eu.torvian.chatbot.common.models
import kotlinx.serialization.Serializable
/**
 * Request body for updating the current model ID of a chat session.
 *
 * @property modelId The new optional model ID for the session. Null means no model selected.
 */
@Serializable
data class UpdateSessionModelRequest(
    val modelId: Long?
)