package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.llm.ModelSettings
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
     * Retrieves all settings profiles accessible by the specified user, either owned by the user
     * or shared with a group the user is a member of.
     *
     * @param userId The ID of the user requesting the settings
     * @param accessMode The access mode to query (e.g., "read", "write")
     * @return List of ModelSettings objects accessible by the user.
     */
    suspend fun getAllAccessibleSettings(userId: Long, accessMode: AccessMode): List<ModelSettings>

    /**
     * Retrieves all settings profiles accessible by the specified user for a specific model.
     * This filters accessible settings (owned or group-shared) by the provided modelId.
     *
     * @param userId The ID of the user requesting the settings
     * @param modelId The model id to filter settings by
     * @param accessMode The access mode to query (e.g., "read", "write")
     * @return List of ModelSettings objects accessible by the user for the given model.
     */
    suspend fun getAccessibleSettingsByModelId(userId: Long, modelId: Long, accessMode: AccessMode): List<ModelSettings>

    /**
     * Creates a new settings profile with the specified parameters.
     *
     * @param settings The ModelSettings object containing all the settings data to insert
     * @return [Either] a [SettingsError.ModelNotFound] if the associated model doesn't exist, or the newly created [ModelSettings]
     */
    suspend fun insertSettings(settings: ModelSettings): Either<SettingsError.ModelNotFound, ModelSettings>

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
