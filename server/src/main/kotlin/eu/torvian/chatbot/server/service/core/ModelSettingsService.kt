package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.service.core.error.settings.*
import kotlinx.serialization.json.JsonObject

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
     * @param name The display name of the settings profile (e.g., "Default", "Creative")
     * @param modelId The ID of the LLM model this settings profile is associated with
     * @param systemMessage Optional system message/prompt to include in the conversation context
     * @param temperature Optional sampling temperature for text generation
     * @param maxTokens Optional maximum number of tokens to generate in the response
     * @param customParams Optional model-specific parameters stored as a [JsonObject]
     * @return [Either] an [AddSettingsError] if the associated model doesn't exist or insertion fails, or the newly created [ModelSettings]
     */
    suspend fun addSettings(
        name: String,
        modelId: Long,
        systemMessage: String?,
        temperature: Float?,
        maxTokens: Int?,
        customParams: JsonObject?
    ): Either<AddSettingsError, ModelSettings>

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
