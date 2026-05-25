package eu.torvian.chatbot.common.models.api.me

import eu.torvian.chatbot.common.models.user.PreferenceScope
import kotlinx.serialization.Serializable

/**
 * Represents a single user preference payload used by the /api/v1/me preferences endpoints.
 *
 * @property key Stable preference key that identifies the setting being updated.
 * @property value Serialized preference value stored as text so callers can send primitives or JSON strings.
 * @property scope Target scope that determines whether the preference is global or device-specific.
 */
@Serializable
data class UserPreferenceDTO(
    val key: String,
    val value: String,
    val scope: PreferenceScope
)

