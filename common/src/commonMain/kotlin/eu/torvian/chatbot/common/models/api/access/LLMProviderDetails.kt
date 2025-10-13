package eu.torvian.chatbot.common.models.api.access

import eu.torvian.chatbot.common.models.llm.LLMProvider
import kotlinx.serialization.Serializable

/**
 * Details about a provider, including its access information.
 *
 * @property provider The provider object
 * @property accessDetails The access details for the provider
 */
@Serializable
data class LLMProviderDetails(
    val provider: LLMProvider,
    val accessDetails: ResourceAccessDetails
){
    /**
     * Checks if the provider is publicly accessible.
     *
     * A provider is considered public if the "All Users" group has READ access.
     *
     * @return True if the provider is public, false otherwise
     */
    fun isPublic(): Boolean = accessDetails.isPublic()

    /**
     * Checks if the provider is private.
     *
     * A provider is considered private if the "All Users" group has no access.
     *
     * @return True if the provider is private, false otherwise
     */
    fun isPrivate(): Boolean = accessDetails.isPrivate()

    /**
     * Returns the username of the provider owner, if available.
     *
     * @return The username of the owner, or null if not available
     */
    fun getOwner(): String? = accessDetails.owner?.username
}
