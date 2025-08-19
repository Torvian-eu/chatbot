package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.ModelSettings
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
     * Creates a new settings profile with the specified parameters.
     *
     * @param settings The ModelSettings object containing all the settings data to create
     * @return [Either] an [AddSettingsError] if the associated model doesn't exist or insertion fails, or the newly created [ModelSettings]
     */
    suspend fun addSettings(settings: ModelSettings): Either<AddSettingsError, ModelSettings>

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
