package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.service.core.error.settings.*

/**
 * Service interface for managing Model Settings.
 */
interface ModelSettingsService {
    /**
     * Retrieves a specific settings profile by ID.
     * @param id The ID of the settings profile.
     * @return Either a [GetSettingsByIdError] if not found, or the [ModelSettings].
     */
    suspend fun getSettingsById(id: Long): Either<GetSettingsByIdError, ModelSettings>

    /**
     * Retrieves all settings profiles stored in the database.
     * @return A list of all [ModelSettings] objects.
     */
    suspend fun getAllSettings(): List<ModelSettings>

    /**
     * Retrieves all settings profiles associated with a specific LLM model.
     * @param modelId The ID of the LLM model.
     * @return A list of [ModelSettings] for the model, or an empty list if none exist.
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
     * Creates a new settings profile with the specified parameters and assigns ownership to the provided user.
     *
     * @param ownerId The ID of the user who will own the newly created settings profile.
     * @param settings The ModelSettings object containing all the settings data to create
     * @return [Either] an [AddSettingsError] if the associated model doesn't exist or insertion fails, or the newly created [ModelSettings]
     */
    suspend fun addSettings(ownerId: Long, settings: ModelSettings): Either<AddSettingsError, ModelSettings>

    /**
     * Updates an existing settings profile with new values.
     *
     * @param settings The ModelSettings object containing the updated values. The ID must match an existing settings profile.
     * @return [Either] an [UpdateSettingsError] if not found or update fails, or [Unit] on success
     */
    suspend fun updateSettings(settings: ModelSettings): Either<UpdateSettingsError, Unit>

    /**
     * Deletes a settings profile with the specified ID.
     *
     * @param id The unique identifier of the settings profile to delete
     * @return [Either] a [DeleteSettingsError] if not found, or [Unit] on success
     */
    suspend fun deleteSettings(id: Long): Either<DeleteSettingsError, Unit>
}
