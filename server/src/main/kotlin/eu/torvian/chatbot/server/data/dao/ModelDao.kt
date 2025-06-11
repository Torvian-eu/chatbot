package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.server.data.dao.error.ModelError

/**
 * Data Access Object for LLMModel entities.
 * 
 * This interface defines the contract for database operations related to LLM models.
 * It provides methods to create, read, update, and delete LLMModel records in the database.
 * The implementation of this interface is responsible for translating between the database
 * representation and the domain model objects.
 */
interface ModelDao {
    /**
     * Retrieves all LLM models from the database.
     * 
     * @return A list of all available LLMModel entities.
     */
    suspend fun getAllModels(): List<LLMModel>
    
    /**
     * Retrieves a single LLM model by its unique identifier.
     * 
     * @param id The unique identifier of the LLM model to retrieve.
     * @return [Either] a [ModelError.ModelNotFound] if the model doesn't exist, or the [LLMModel].
     */
    suspend fun getModelById(id: Long): Either<ModelError.ModelNotFound, LLMModel>

    /**
     * Retrieves a single LLM model by its associated API key identifier.
     * 
     * This method is useful for lookup operations when an API key reference is available
     * but the model ID is not known.
     * 
     * @param apiKeyId The API key reference ID associated with the model.
     * @return The matching LLMModel if found, or null if no model exists with the given API key ID.
     */
    suspend fun getModelByApiKeyId(apiKeyId: String): LLMModel?

    /**
     * Creates a new LLM model in the database.
     * 
     * @param name The display name for the model.
     * @param baseUrl The base URL for the LLM API endpoint.
     * @param type The type of LLM provider (e.g., "openai", "openrouter", "custom").
     * @param apiKeyId Optional reference ID to the securely stored API key.
     * @return The newly created LLMModel with its assigned ID.
     */
    suspend fun insertModel(name: String, baseUrl: String, type: String, apiKeyId: String?): LLMModel

    /**
     * Updates an existing LLM model in the database.
     * 
     * All properties of the provided model will be updated, including the name,
     * baseUrl, type, and apiKeyId.
     * 
     * @param model The LLMModel containing the updated values.
     * @return [Either] a [ModelError.ModelNotFound] if the model doesn't exist, or [Unit] on success.
     */
    suspend fun updateModel(model: LLMModel): Either<ModelError.ModelNotFound, Unit>

    /**
     * Deletes an LLM model from the database.
     * 
     * @param id The unique identifier of the LLM model to delete.
     * @return [Either] a [ModelError.ModelNotFound] if the model doesn't exist, or [Unit] on success.
     */
    suspend fun deleteModel(id: Long): Either<ModelError.ModelNotFound, Unit>
}
