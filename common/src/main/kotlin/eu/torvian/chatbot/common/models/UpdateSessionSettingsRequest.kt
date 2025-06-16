package eu.torvian.chatbot.common.models
import kotlinx.serialization.Serializable
/**
 * Request body for updating the current settings ID of a chat session.
 *
 * @property settingsId The new optional settings ID for the session. Null means no settings selected.
 */
@Serializable
data class UpdateSessionSettingsRequest(
    val settingsId: Long?
)