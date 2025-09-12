package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.AddModelRequest
import eu.torvian.chatbot.common.models.ApiKeyStatusResponse
import eu.torvian.chatbot.common.models.LLMModel

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
     * @param request The request body with model details.
     * @return [Either.Right] containing the newly created [LLMModel] object on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., invalid input, provider not found, model name already exists).
     */
    suspend fun addModel(request: AddModelRequest): Either<ApiResourceError, LLMModel>

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
}