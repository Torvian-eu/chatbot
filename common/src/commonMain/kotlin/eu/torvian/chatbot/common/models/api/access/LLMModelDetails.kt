package eu.torvian.chatbot.common.models.api.access

import eu.torvian.chatbot.common.models.llm.LLMModel
import kotlinx.serialization.Serializable

/**
 * Details about a model, including its access information.
 *
 * @property model The model object
 * @property accessDetails The access details for the model
 */
@Serializable
data class LLMModelDetails(
    val model: LLMModel,
    val accessDetails: ResourceAccessDetails
) {
    /**
     * Checks if the model is publicly accessible.
     *
     * A model is considered public if the "All Users" group has READ access.
     *
     * @return True if the model is public, false otherwise
     */
    fun isPublic(): Boolean = accessDetails.isPublic()

    /**
     * Checks if the model is private.
     *
     * A model is considered private if the "All Users" group has no access.
     *
     * @return True if the model is private, false otherwise
     */
    fun isPrivate(): Boolean = accessDetails.isPrivate()

    /**
     * Returns the username of the model owner, if available.
     *
     * @return The username of the owner, or null if not available
     */
    fun getOwner(): String? = accessDetails.owner?.username
}

