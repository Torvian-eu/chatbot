package eu.torvian.chatbot.common.models.api.core
import kotlinx.serialization.Serializable
/**
 * Request body for updating the current model ID of a chat session.
 *
 * @property modelId The new optional model ID for the session. Null means no model selected.
 * @property autoSelectFirstAvailableSettings When true, the backend will try to pick the first
 * compatible settings profile for the selected model.
 */
@Serializable
data class UpdateSessionModelRequest(
    val modelId: Long?,
    val autoSelectFirstAvailableSettings: Boolean = false
)