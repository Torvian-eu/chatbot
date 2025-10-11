package eu.torvian.chatbot.common.models.api.access

import eu.torvian.chatbot.common.models.llm.ModelSettings
import kotlinx.serialization.Serializable

/**
 * Details about a model settings profile, including its access information.
 *
 * @property settings The settings object
 * @property accessDetails The access details for the settings profile
 */
@Serializable
data class ModelSettingsDetails(
    val settings: ModelSettings,
    val accessDetails: ResourceAccessDetails
) {
    /**
     * Checks if the settings profile is publicly accessible.
     *
     * A settings profile is considered public if the "All Users" group has READ access.
     *
     * @return True if the settings profile is public, false otherwise
     */
    fun isPublic(): Boolean = accessDetails.isPublic()

    /**
     * Checks if the settings profile is private.
     *
     * A settings profile is considered private if the "All Users" group has no access.
     *
     * @return True if the settings profile is private, false otherwise
     */
    fun isPrivate(): Boolean = accessDetails.isPrivate()

    /**
     * Returns the username of the settings owner, if available.
     *
     * @return The username of the owner, or null if not available
     */
    fun getOwner(): String? = accessDetails.owner?.username
}

