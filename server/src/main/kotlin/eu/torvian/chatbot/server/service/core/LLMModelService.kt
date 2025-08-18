package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMModelType
import eu.torvian.chatbot.server.service.core.error.model.*
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
     * Adds a new LLM model configuration.
     * @param name The unique identifier for the model (e.g., "gpt-3.5-turbo").
     * @param providerId The ID of the provider that hosts this model.
     * @param type The operational type of this model (e.g., CHAT, EMBEDDING, etc.).
     * @param active Whether the model is currently active and available for use.
     * @param displayName Optional display name for UI purposes.
     * @param capabilities Optional JSON object containing model capabilities.
     * @return Either an [AddModelError], or the newly created [LLMModel].
     */
    suspend fun addModel(
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
}
