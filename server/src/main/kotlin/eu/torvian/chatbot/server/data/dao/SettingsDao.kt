package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.dao.error.SettingsError

/**
 * Data Access Object for ModelSettings entities.
 * 
 * Provides operations to create, read, update, and delete settings profiles for LLM models.
 * Each settings profile contains parameters that customize model behavior such as temperature,
 * max tokens, system message, and other custom parameters.
 */
interface SettingsDao {
    /**
     * Retrieves a single settings profile by its unique ID.
     *
     * @param id The unique identifier of the settings profile to retrieve
     * @return [Either] a [SettingsError.SettingsNotFound] if the settings don't exist, or the [ModelSettings]
     */
    suspend fun getSettingsById(id: Long): Either<SettingsError.SettingsNotFound, ModelSettings>

    /**
     * Retrieves all settings profiles stored in the database.
     *
     * @return A list of all ModelSettings objects, or an empty list if none exist
     */
    suspend fun getAllSettings(): List<ModelSettings>
    
    /**
     * Retrieves all settings profiles associated with a specific LLM model.
     *
     * @param modelId The unique identifier of the LLM model to fetch settings for
     * @return A list of ModelSettings objects for the specified model, or an empty list if none exist
     */
    suspend fun getSettingsByModelId(modelId: Long): List<ModelSettings>
    
    /**
     * Creates a new settings profile with the specified parameters.
     *
     * @param name The display name of the settings profile (e.g., "Default", "Creative")
     * @param modelId The ID of the LLM model this settings profile is associated with
     * @param systemMessage Optional system message/prompt to include in the conversation context
     * @param temperature Optional sampling temperature for text generation
     * @param maxTokens Optional maximum number of tokens to generate in the response
     * @param customParamsJson Optional model-specific parameters stored as a JSON string
     * @return [Either] a [SettingsError.ModelNotFound] if the associated model doesn't exist, or the newly created [ModelSettings]
     */
    suspend fun insertSettings(
        name: String,
        modelId: Long,
        systemMessage: String?,
        temperature: Float?,
        maxTokens: Int?,
        customParamsJson: String?
    ): Either<SettingsError.ModelNotFound, ModelSettings>

    /**
     * Updates an existing settings profile with new values.
     *
     * @param settings The ModelSettings object containing updated values and the ID of the profile to update
     * @return [Either] a [SettingsError] if the update fails, or [Unit] on success
     */
    suspend fun updateSettings(settings: ModelSettings): Either<SettingsError, Unit>

    /**
     * Deletes a settings profile with the specified ID.
     *
     * @param id The unique identifier of the settings profile to delete
     * @return [Either] a [SettingsError.SettingsNotFound] if the settings don't exist, or [Unit] on success
     */
    suspend fun deleteSettings(id: Long): Either<SettingsError.SettingsNotFound, Unit>
}
