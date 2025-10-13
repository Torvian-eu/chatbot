package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.server.service.core.error.access.GrantResourceAccessError
import eu.torvian.chatbot.server.service.core.error.access.MakeResourcePrivateError
import eu.torvian.chatbot.server.service.core.error.access.MakeResourcePublicError
import eu.torvian.chatbot.server.service.core.error.access.RevokeResourceAccessError
import eu.torvian.chatbot.server.service.core.error.model.AddModelError
import eu.torvian.chatbot.server.service.core.error.model.DeleteModelError
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.model.UpdateModelError
import kotlinx.serialization.json.JsonObject

/**
 * Service interface for managing LLM Models.
 */
interface LLMModelService {
    /**
     * Retrieves all LLM model configurations.
     */
    suspend fun getAllModels(): List<LLMModel>

    /**
     * Retrieves a single LLM model by its unique identifier.
     *
     * @param id The unique identifier of the LLM model to retrieve.
     * @return [Either] a [GetModelError], or the [LLMModel].
     */
    suspend fun getModelById(id: Long): Either<GetModelError, LLMModel>

    /**
     * Retrieves all LLM models associated with a specific provider.
     * @param providerId The ID of the provider to get models for.
     * @return A list of LLMModel entities associated with the provider.
     */
    suspend fun getModelsByProviderId(providerId: Long): List<LLMModel>

    /**
     * Retrieves all models accessible by the specified user, either owned by the user
     * or shared with a group the user is a member of.
     *
     * @param userId The ID of the user requesting the models
     * @param accessMode The access mode to query (e.g., "read", "write")
     * @return List of LLMModel objects accessible by the user.
     */
    suspend fun getAllAccessibleModels(userId: Long, accessMode: AccessMode): List<LLMModel>

    /**
     * Retrieves all models accessible by the specified user for a specific provider.
     * Filters accessible models (owned or group-shared) by the provided providerId.
     *
     * @param userId The ID of the user requesting the models
     * @param providerId The provider id to filter models by
     * @param accessMode The access mode to query (e.g., "read", "write")
     * @return List of LLMModel objects accessible by the user for the given provider.
     */
    suspend fun getAccessibleModelsByProviderId(userId: Long, providerId: Long, accessMode: AccessMode): List<LLMModel>

    /**
     * Adds a new LLM model configuration.
     * @param ownerId The ID of the user creating the model (owner).
     * @param name The unique identifier for the model (e.g., "gpt-3.5-turbo").
     * @param providerId The ID of the provider that hosts this model.
     * @param type The operational type of this model (e.g., CHAT, EMBEDDING, etc.).
     * @param active Whether the model is currently active and available for use.
     * @param displayName Optional display name for UI purposes.
     * @param capabilities Optional JSON object containing model capabilities.
     * @return Either an [AddModelError], or the newly created [LLMModel].
     */
    suspend fun addModel(
        ownerId: Long,
        name: String,
        providerId: Long,
        type: LLMModelType,
        active: Boolean = true,
        displayName: String? = null,
        capabilities: JsonObject? = null
    ): Either<AddModelError, LLMModel>

    /**
     * Updates an existing LLM model configuration.
     * @param model The LLMModel object containing the updated values. The ID must match an existing model.
     * @return Either an [UpdateModelError] or Unit if successful.
     */
    suspend fun updateModel(model: LLMModel): Either<UpdateModelError, Unit>

    /**
     * Deletes an LLM model configuration.
     * Handles deletion of associated settings. Does not delete the API key itself.
     * @param id The ID of the model to delete.
     * @return Either a [DeleteModelError], or Unit if successful.
     */
    suspend fun deleteModel(id: Long): Either<DeleteModelError, Unit>

    /**
     * Checks if an API key is configured for a specific model.
     * This checks if the model's provider has a valid API key configured.
     * @param modelId The ID of the model.
     * @return True if the model's provider has an API key configured, false otherwise.
     */
    suspend fun isApiKeyConfiguredForModel(modelId: Long): Boolean

    // --- Access Management ---

    /**
     * Grants access to a model for a specific user group with the specified access mode.
     *
     * @param modelId The ID of the model to grant access to
     * @param groupId The ID of the user group to grant access
     * @param accessMode The access mode to grant (e.g., AccessMode.READ, AccessMode.WRITE)
     * @return Either [GrantResourceAccessError] or Unit on success
     */
    suspend fun grantModelAccess(
        modelId: Long,
        groupId: Long,
        accessMode: AccessMode
    ): Either<GrantResourceAccessError, Unit>

    /**
     * Revokes access to a model from a specific user group for the specified access mode.
     *
     * @param modelId The ID of the model to revoke access from
     * @param groupId The ID of the user group to revoke access from
     * @param accessMode The access mode to revoke
     * @return Either [RevokeResourceAccessError] or Unit on success
     */
    suspend fun revokeModelAccess(
        modelId: Long,
        groupId: Long,
        accessMode: AccessMode
    ): Either<RevokeResourceAccessError, Unit>

    /**
     * Retrieves model details, including the owner and all groups with access.
     *
     * @param modelId The ID of the model to query
     * @return Either [GetModelError] or [LLMModelDetails]
     */
    suspend fun getModelDetails(modelId: Long): Either<GetModelError, LLMModelDetails>

    // --- Convenience Methods ---

    /**
     * Makes a model publicly accessible by granting READ access to the "All Users" group.
     *
     * This is a convenience method that internally grants READ access to the special
     * "All Users" group, making the model visible to all users in the system.
     *
     * @param modelId The ID of the model to make public
     * @return Either [MakeResourcePublicError] or Unit on success
     */
    suspend fun makeModelPublic(modelId: Long): Either<MakeResourcePublicError, Unit>

    /**
     * Makes a model private by revoking all access from the "All Users" group.
     *
     * This is a convenience method that removes all access from
     * the "All Users" group, making the model accessible only to users with
     * explicit group access or the owner.
     *
     * @param modelId The ID of the model to make private
     * @return Either [MakeResourcePrivateError] or Unit on success
     */
    suspend fun makeModelPrivate(modelId: Long): Either<MakeResourcePrivateError, Unit>
}
