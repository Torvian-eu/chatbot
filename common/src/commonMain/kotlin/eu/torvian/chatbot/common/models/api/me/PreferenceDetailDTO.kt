package eu.torvian.chatbot.common.models.api.me

import kotlinx.serialization.Serializable

/**
 * Represents the detailed view of a preference showing both global and device-specific values.
 *
 * This DTO is used by the Settings UI to display the inheritance chain, allowing users
 * to see which value is effective and whether a device override exists.
 *
 * @property globalValue The global preference value, or `null` if not set globally.
 * @property deviceValue The device-specific preference value, or `null` if not set for this device.
 */
@Serializable
data class PreferenceDetailDTO(
    val globalValue: String?,
    val deviceValue: String?
)
