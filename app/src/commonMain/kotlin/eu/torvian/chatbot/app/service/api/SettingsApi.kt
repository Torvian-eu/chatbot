package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.ModelSettings

/**
 * Frontend API interface for interacting with Model Settings Profile-related endpoints.
 *
 * This interface defines the operations for managing LLM settings profiles.
 * Implementations use the internal HTTP API. All methods are suspend functions
 * and return [Either<ApiResourceError, T>].
 */
interface SettingsApi {
    /**
     * Retrieves a list of all settings profiles associated with a specific LLM model.
     *
     * Corresponds to `GET /api/v1/models/{modelId}/settings`.
     * (E4.S5)
     *
     * @param modelId The ID of the model whose settings profiles to retrieve.
     * @return [Either.Right] containing a list of [ModelSettings] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., model not found).
     */
    suspend fun getSettingsByModelId(modelId: Long): Either<ApiResourceError, List<ModelSettings>>

    /**
     * Creates a new settings profile.
     *
     * Corresponds to `POST /api/v1/settings`.
     * (E4.S5)
     *
     * @param settings The [ModelSettings] object to create.
     * @return [Either.Right] containing the newly created [ModelSettings] object on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., invalid input, model not found).
     */
    suspend fun addModelSettings(settings: ModelSettings): Either<ApiResourceError, ModelSettings>

    /**
     * Retrieves details for a specific settings profile.
     *
     * Corresponds to `GET /api/v1/settings/{settingsId}`.
     *
     * @param settingsId The ID of the settings profile to retrieve.
     * @return [Either.Right] containing the requested [ModelSettings] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getSettingsById(settingsId: Long): Either<ApiResourceError, ModelSettings>

    /**
     * Updates the parameters of a specific settings profile.
     *
     * Corresponds to `PUT /api/v1/settings/{settingsId}`.
     * (E4.S6)
     *
     * @param settings The [ModelSettings] object with updated details. The ID must match the path.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, invalid input).
     */
    suspend fun updateSettings(settings: ModelSettings): Either<ApiResourceError, Unit>

    /**
     * Deletes a specific settings profile.
     * (E4.S5)
     *
     * @param settingsId The ID of the settings profile to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun deleteSettings(settingsId: Long): Either<ApiResourceError, Unit>

    /**
     * Retrieves all settings profiles from the server.
     *
     * Corresponds to `GET /api/v1/settings`.
     *
     * @return [Either.Right] containing a list of all [ModelSettings] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getAllSettings(): Either<ApiResourceError, List<ModelSettings>>
}