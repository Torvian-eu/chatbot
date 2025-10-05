package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
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
     * Reactive stream of all model settings profiles.
     *
     * This StateFlow provides real-time updates whenever the settings data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all settings wrapped in DataState
     */
    val settings: StateFlow<DataState<RepositoryError, List<ModelSettings>>>

    /**
     * Returns a cold Flow for a specific model settings profile that derives from the main settings StateFlow.
     *
     * This method provides a cold Flow that transforms the main `settings` stream to emit
     * the state of an individual settings profile whenever it changes. As a cold flow, it only
     * starts emitting when collected by a consumer (like a ViewModel).
     * The consumer is responsible for converting this to a hot StateFlow if needed.
     *
     * @param settingsId The unique identifier of the settings profile to observe
     * @return Flow containing the current state of the specific settings profile wrapped in DataState
     */
    fun getSettingsFlow(settingsId: Long): Flow<DataState<RepositoryError, ModelSettings>>

    /**
     * Loads all model settings profiles from the backend.
     *
     * This operation fetches the latest settings data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with List<ModelSettings> on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadSettings(): Either<RepositoryError, List<ModelSettings>>

    /**
     * Loads a specific model settings profile from the backend.
     *
     * This operation fetches the latest settings data for the specified ID and updates
     * the individual settings StateFlow. If a load operation for this settings profile is already
     * in progress, this method returns immediately without starting a duplicate operation.
     *
     * @param settingsId The unique identifier of the settings profile to load
     * @return Either.Right with the loaded ModelSettings on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadSettingsDetails(settingsId: Long): Either<RepositoryError, ModelSettings>

    /**
     * Loads settings profiles for a specific model from the backend.
     *
     * This operation fetches settings data for a particular model and can be used
     * to filter the settings data by model ID.
     *
     * @param modelId The unique identifier of the model whose settings to load
     * @return Either.Right with a list of ModelSettings on success, or Either.Left with RepositoryError on failure
     */
    suspend fun loadSettingsByModelId(modelId: Long): Either<RepositoryError, List<ModelSettings>>

    /**
     * Creates a new model settings profile.
     *
     * Upon successful creation, the new settings profile is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param settings The ModelSettings object to create
     * @return Either.Right with the created ModelSettings on success, or Either.Left with RepositoryError on failure
     */
    suspend fun addModelSettings(settings: ModelSettings): Either<RepositoryError, ModelSettings>

    /**
     * Retrieves a specific model settings profile by its ID.
     *
     * This method provides direct access to a single settings profile without affecting
     * the main settings StateFlow.
     *
     * @param settingsId The unique identifier of the settings profile to retrieve
     * @return Either.Right with the ModelSettings on success, or Either.Left with RepositoryError on failure
     */
    suspend fun getSettingsById(settingsId: Long): Either<RepositoryError, ModelSettings>

    /**
     * Updates an existing model settings profile.
     *
     * Upon successful update, the modified settings profile replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param settings The updated settings object with the same ID as the existing profile
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateSettings(settings: ModelSettings): Either<RepositoryError, Unit>

    /**
     * Deletes a model settings profile.
     *
     * Upon successful deletion, the settings profile is automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param settingsId The unique identifier of the settings profile to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteSettings(settingsId: Long): Either<RepositoryError, Unit>
}
