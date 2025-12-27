package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.llm.ApiKeyStatusResponse
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import kotlinx.serialization.json.JsonObject

/**
 * Frontend API interface for interacting with LLM Model-related endpoints.
 *
 * This interface defines the operations for managing specific LLM model configurations.
 * Implementations use the internal HTTP API. All methods are suspend functions
 * and return [Either<ApiResourceError, T>].
 */
interface ModelApi {
    /**
     * Retrieves a list of all configured LLM models across all providers.
     *
     * Corresponds to `GET /api/v1/models`.
     * (E4.S2)
     *
     * @return [Either.Right] containing a list of [LLMModel] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getAllModels(): Either<ApiResourceError, List<LLMModel>>

    /**
     * Adds a new LLM model configuration linked to an existing provider.
     *
     * Corresponds to `POST /api/v1/models`.
     * (E4.S1)
     *
     * @param name The unique identifier for the model (e.g., "gpt-3.5-turbo", "gpt-4").
     * @param providerId The ID of the provider that hosts this model.
     * @param type The operational type of this model (e.g., CHAT, EMBEDDING, etc.).
     * @param active Whether the model is currently active and available for use.
     * @param displayName Optional display name for UI purposes.
     * @param capabilities Optional JSON object containing model capabilities.
     * @return [Either.Right] containing the newly created [LLMModel] object on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., invalid input, provider not found, model name already exists).
     */
    suspend fun addModel(
        name: String,
        providerId: Long,
        type: LLMModelType,
        active: Boolean = true,
        displayName: String? = null,
        capabilities: JsonObject? = null
    ): Either<ApiResourceError, LLMModel>

    /**
     * Retrieves details for a specific LLM model configuration.
     *
     * Corresponds to `GET /api/v1/models/{modelId}`.
     *
     * @param modelId The ID of the model to retrieve.
     * @return [Either.Right] containing the requested [LLMModel] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getModelById(modelId: Long): Either<ApiResourceError, LLMModel>

    /**
     * Updates details for a specific LLM model configuration.
     *
     * Corresponds to `PUT /api/v1/models/{modelId}`.
     * (E4.S3)
     *
     * @param model The [LLMModel] object with updated details. The ID must match the path.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, invalid input, model name already exists).
     */
    suspend fun updateModel(model: LLMModel): Either<ApiResourceError, Unit>

    /**
     * Deletes an LLM model configuration.
     *
     * Corresponds to `DELETE /api/v1/models/{modelId}`.
     * (E4.S4)
     *
     * @param modelId The ID of the model to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun deleteModel(modelId: Long): Either<ApiResourceError, Unit>

    /**
     * Checks if an API key credential has been configured for the provider linked to this model.
     * Does not return the key itself.
     *
     * Corresponds to `GET /api/v1/models/{modelId}/apikey/status`.
     * (E5.S4)
     *
     * @param modelId The ID of the model.
     * @return [Either.Right] containing an [ApiKeyStatusResponse] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getModelApiKeyStatus(modelId: Long): Either<ApiResourceError, ApiKeyStatusResponse>

    /**
     * Retrieves detailed information about a model, including owner and access list.
     *
     * Corresponds to `GET /api/v1/models/{modelId}/details`.
     *
     * @param modelId The ID of the model
     * @return Either.Right with [LLMModelDetails] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun getModelDetails(modelId: Long): Either<ApiResourceError, LLMModelDetails>

    /**
     * Retrieves detailed information about all models accessible to the current user.
     *
     * Corresponds to `GET /api/v1/models/details`.
     *
     * @return Either.Right with list of [LLMModelDetails] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun getAllModelDetails(): Either<ApiResourceError, List<LLMModelDetails>>

    /**
     * Makes a model publicly accessible by granting READ access to the "All Users" group.
     *
     * Corresponds to `POST /api/v1/models/{modelId}/make-public`.
     *
     * @param modelId The ID of the model to make public
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun makeModelPublic(modelId: Long): Either<ApiResourceError, Unit>

    /**
     * Makes a model private by revoking all access from the "All Users" group.
     *
     * Corresponds to `POST /api/v1/models/{modelId}/make-private`.
     *
     * @param modelId The ID of the model to make private
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun makeModelPrivate(modelId: Long): Either<ApiResourceError, Unit>

    /**
     * Grants access to a model for a specific user group with the specified access mode.
     *
     * Corresponds to `POST /api/v1/models/{modelId}/access`.
     *
     * @param modelId The ID of the model
     * @param groupId The ID of the user group
     * @param accessMode The access mode to grant
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun grantModelAccess(
        modelId: Long,
        groupId: Long,
        accessMode: String
    ): Either<ApiResourceError, Unit>

    /**
     * Revokes access to a model from a specific user group for the specified access mode.
     *
     * Corresponds to `DELETE /api/v1/models/{modelId}/access`.
     *
     * @param modelId The ID of the model
     * @param groupId The ID of the user group
     * @param accessMode The access mode to revoke
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun revokeModelAccess(
        modelId: Long,
        groupId: Long,
        accessMode: String
    ): Either<ApiResourceError, Unit>
}