package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.llm.ModelSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing LLM model settings profiles.
 *
 * This repository serves as the single source of truth for settings data in the application,
 * providing reactive data streams through StateFlow and handling all settings-related operations.
 * It abstracts the underlying API layer and provides comprehensive error handling through
 * the RepositoryError hierarchy.
 *
 * The repository follows a clean architecture where it provides access to data without
 * managing the lifecycle of individual observations, leaving state management to consumers.
 */
interface SettingsRepository {

    /**
     * Reactive stream of all LLM settings profiles, including ownership and access details.
     *
     * This StateFlow provides real-time updates whenever the settings data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all settings wrapped in DataState
     */
    val allSettingsDetails: StateFlow<DataState<RepositoryError, List<ModelSettingsDetails>>>

    /**
     * Reactive stream of all LLM settings profiles.
     *
     * This one should be used instead of allSettingsDetails() when access details are not needed.
     *
     * @return StateFlow containing the current state of all settings wrapped in DataState
     */
    val allSettings: StateFlow<DataState<RepositoryError, List<ModelSettings>>>

    /**
     * Returns a cold Flow for a specific LLM settings profile that derives from the main settings StateFlow.
     *
     * This method provides a cold Flow that transforms the main `settings` stream to emit
     * the state of an individual settings profile whenever it changes. As a cold flow, it only
     * starts emitting when collected by a consumer (like a ViewModel).
     * The consumer is responsible for converting this to a hot StateFlow if needed.
     *
     * @param settingsId The unique identifier of the settings profile to observe
     * @return Flow containing the current state of the specific settings profile wrapped in DataState
     */
    fun getSettingsFlow(settingsId: Long): Flow<DataState<RepositoryError, ModelSettingsDetails?>>

    /**
     * Loads all LLM settings profiles from the backend, including ownership and access details.
     *
     * This operation fetches the latest settings data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadAllSettingsDetails(): Either<RepositoryError, Unit>

    /**
     * Loads all LLM settings profiles from the backend.
     *
     * This one should be used instead of loadAllSettingsDetails() when access details are not needed.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadAllSettings(): Either<RepositoryError, Unit>

    /**
     * Loads detailed information about a specific LLM settings profile, including owner and access list.
     *
     * This operation fetches the latest settings details and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @param settingsId The unique identifier of the settings profile to load
     * @return Either.Right with ModelSettingsDetails on success, or Either.Left with RepositoryError on failure
     */
    suspend fun loadSettingsDetails(settingsId: Long): Either<RepositoryError, ModelSettingsDetails>

    /**
     * Creates a new model settings profile.
     *
     * Upon successful creation, the new settings profile's details are automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param settings The ModelSettings object to create
     * @return Either.Right with the created ModelSettingsDetails on success, or Either.Left with RepositoryError on failure
     */
    suspend fun addModelSettings(settings: ModelSettings): Either<RepositoryError, ModelSettingsDetails>

    /**
     * Updates an existing model settings profile.
     *
     * Upon successful update, the modified settings profile's details replace the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param settings The updated settings object with the same ID as the existing profile
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSettings(settings: ModelSettings): Either<RepositoryError, Unit>

    /**
     * Deletes a model settings profile.
     *
     * Upon successful deletion, the settings profile's details are automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param settingsId The unique identifier of the settings profile to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteSettings(settingsId: Long): Either<RepositoryError, Unit>

    /**
     * Makes a settings profile publicly accessible by granting READ access to the "All Users" group.
     *
     * Upon successful operation, the internal StateFlow of settings details is updated.
     *
     * @param settingsId The ID of the settings profile to make public.
     * @return Either.Right with [Unit] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun makeSettingsPublic(settingsId: Long): Either<RepositoryError, Unit>

    /**
     * Makes a settings profile private by revoking all access from the "All Users" group.
     *
     * Upon successful operation, the internal StateFlow of settings details is updated.
     *
     * @param settingsId The ID of the settings profile to make private.
     * @return Either.Right with [Unit] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun makeSettingsPrivate(settingsId: Long): Either<RepositoryError, Unit>

    /**
     * Grants access to a settings profile for a specific user group with the specified access mode.
     *
     * Upon successful operation, the internal StateFlow of settings details is updated.
     *
     * @param settingsId The ID of the settings profile.
     * @param request The grant access request containing groupId and accessMode.
     * @return Either.Right with [ModelSettingsDetails] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun grantSettingsAccess(
        settingsId: Long,
        request: GrantAccessRequest
    ): Either<RepositoryError, ModelSettingsDetails>

    /**
     * Revokes access to a settings profile from a specific user group for the specified access mode.
     *
     * Upon successful operation, the internal StateFlow of settings details is updated.
     *
     * @param settingsId The ID of the settings profile.
     * @param request The revoke access request containing groupId and accessMode.
     * @return Either.Right with [ModelSettingsDetails] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun revokeSettingsAccess(
        settingsId: Long,
        request: RevokeAccessRequest
    ): Either<RepositoryError, ModelSettingsDetails>
}
