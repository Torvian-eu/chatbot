package eu.torvian.chatbot.common.models.user

import kotlinx.serialization.Serializable

/**
 * Describes the audience that should receive a preference value.
 *
 * Global preferences are shared by every device, while device-scoped preferences only apply
 * to the specific client device that stored them.
 */
@Serializable
enum class PreferenceScope {
    /**
     * Applies the preference to every authenticated device for the user.
     */
    GLOBAL,

    /**
     * Applies the preference only to the device that owns the record.
     */
    DEVICE
}

