package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails

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

    /**
     * Retrieves detailed information about a settings profile, including owner and access list.
     *
     * Corresponds to `GET /api/v1/settings/{settingsId}/details`.
     *
     * @param settingsId The ID of the settings profile
     * @return Either.Right with [ModelSettingsDetails] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun getSettingsDetails(settingsId: Long): Either<ApiResourceError, ModelSettingsDetails>

    /**
     * Retrieves detailed information about all settings profiles accessible to the current user.
     *
     * Corresponds to `GET /api/v1/settings/details`.
     *
     * @return Either.Right with list of [ModelSettingsDetails] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun getAllSettingsDetails(): Either<ApiResourceError, List<ModelSettingsDetails>>

    /**
     * Makes a settings profile publicly accessible by granting READ access to the "All Users" group.
     *
     * Corresponds to `POST /api/v1/settings/{settingsId}/make-public`.
     *
     * @param settingsId The ID of the settings profile to make public
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun makeSettingsPublic(settingsId: Long): Either<ApiResourceError, Unit>

    /**
     * Makes a settings profile private by revoking all access from the "All Users" group.
     *
     * Corresponds to `POST /api/v1/settings/{settingsId}/make-private`.
     *
     * @param settingsId The ID of the settings profile to make private
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun makeSettingsPrivate(settingsId: Long): Either<ApiResourceError, Unit>

    /**
     * Grants access to a settings profile for a specific user group with the specified access mode.
     *
     * Corresponds to `POST /api/v1/settings/{settingsId}/access`.
     *
     * @param settingsId The ID of the settings profile
     * @param request The grant access request containing groupId and accessMode
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun grantSettingsAccess(
        settingsId: Long,
        request: GrantAccessRequest
    ): Either<ApiResourceError, Unit>

    /**
     * Revokes access to a settings profile from a specific user group for the specified access mode.
     *
     * Corresponds to `DELETE /api/v1/settings/{settingsId}/access`.
     *
     * @param settingsId The ID of the settings profile
     * @param request The revoke access request containing groupId and accessMode
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun revokeSettingsAccess(
        settingsId: Long,
        request: RevokeAccessRequest
    ): Either<ApiResourceError, Unit>
}