package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.server.data.dao.error.InsertModelError
import eu.torvian.chatbot.server.data.dao.error.ModelError
import eu.torvian.chatbot.server.data.dao.error.UpdateModelError
import kotlinx.serialization.json.JsonObject

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
     * Retrieves all LLM models associated with a specific provider.
     *
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
     * Creates a new LLM model in the database.
     *
     * @param name The unique identifier for the model (e.g., "gpt-3.5-turbo").
     * @param providerId The ID of the provider that hosts this model.
     * @param type The operational type of this model (e.g., CHAT, EMBEDDING, etc.).
     * @param active Whether the model is currently active and available for use.
     * @param displayName Optional display name for UI purposes.
     * @param capabilities Optional JSON object containing model capabilities.
     * @return Either an [InsertModelError] or the newly created LLMModel with its assigned ID.
     */
    suspend fun insertModel(
        name: String,
        providerId: Long,
        type: LLMModelType,
        active: Boolean = true,
        displayName: String? = null,
        capabilities: JsonObject? = null
    ): Either<InsertModelError, LLMModel>

    /**
     * Updates an existing LLM model in the database.
     *
     * All properties of the provided model will be updated, including the name,
     * providerId, and active status.
     *
     * @param model The LLMModel containing the updated values.
     * @return [Either] an [UpdateModelError] or [Unit] on success.
     */
    suspend fun updateModel(model: LLMModel): Either<UpdateModelError, Unit>

    /**
     * Deletes an LLM model from the database. Also deletes associated settings.
     *
     * @param id The unique identifier of the LLM model to delete.
     * @return [Either] a [ModelError.ModelNotFound] if the model doesn't exist, or [Unit] on success.
     */
    suspend fun deleteModel(id: Long): Either<ModelError.ModelNotFound, Unit>
}
