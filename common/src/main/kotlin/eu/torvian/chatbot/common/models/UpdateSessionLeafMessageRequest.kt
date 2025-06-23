package eu.torvian.chatbot.common.models
import kotlinx.serialization.Serializable
/**
 * Request body for updating the current leaf message ID of a chat session.
 *
 * @property leafMessageId The new optional leaf message ID for the session. Null means no leaf message (session has no messages).
 */
@Serializable
data class UpdateSessionLeafMessageRequest(
    val leafMessageId: Long?
)