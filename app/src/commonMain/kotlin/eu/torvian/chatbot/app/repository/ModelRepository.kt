package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.api.llm.AddModelRequest
import eu.torvian.chatbot.common.models.api.llm.ApiKeyStatusResponse
import eu.torvian.chatbot.common.models.llm.LLMModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing LLM model configurations.
 *
 * This repository serves as the single source of truth for model data in the application,
 * providing reactive data streams and handling all model-related operations.
 * It abstracts the underlying API layer and provides comprehensive error handling through
 * the RepositoryError hierarchy.
 *
 * The repository follows a clean architecture where it provides access to data without
 * managing the lifecycle of individual observations, leaving state management to consumers.
 */
interface ModelRepository {

    /**
     * Reactive stream of all LLM model configurations, including ownership and access details.
     *
     * This StateFlow provides real-time updates whenever the model data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all models wrapped in DataState
     */
    val modelsDetails: StateFlow<DataState<RepositoryError, List<LLMModelDetails>>>

    /**
     * Reactive stream of all LLM model configurations.
     *
     * This one should be used instead of modelsDetails() when access details are not needed.
     *
     * @return StateFlow containing the current state of all models wrapped in DataState
     */
    val models: StateFlow<DataState<RepositoryError, List<LLMModel>>>

    /**
     * Returns a cold Flow for a specific LLM model that derives from the main modelDetails StateFlow.
     *
     * This method provides a cold Flow that transforms the main `modelDetails` stream to emit
     * the state of an individual model whenever it changes. As a cold flow, it only
     * starts emitting when collected by a consumer (like a ViewModel).
     * The consumer is responsible for converting this to a hot StateFlow if needed.
     *
     * @param modelId The unique identifier of the model to observe
     * @return Flow containing the current state of the specific model wrapped in DataState
     */
    fun getModelFlow(modelId: Long): Flow<DataState<RepositoryError, LLMModelDetails?>>

    /**
     * Loads all LLM model configurations from the backend, including ownership and access details.
     *
     * This operation fetches the latest model data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadModelsDetails(): Either<RepositoryError, Unit>

    /**
     * Loads all LLM model configurations from the backend.
     *
     * This one should be used instead of loadModelsDetails() when access details are not needed.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadModels(): Either<RepositoryError, Unit>

    /**
     * Loads detailed information about a specific LLM model, including owner and access list.
     *
     * This operation fetches the latest model details and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @param modelId The unique identifier of the model to load
     * @return Either.Right with LLMModelDetails on success, or Either.Left with RepositoryError on failure
     */
    suspend fun loadModelDetails(modelId: Long): Either<RepositoryError, LLMModelDetails>

    /**
     * Adds a new LLM model configuration.
     *
     * Upon successful creation, the new model's details are automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param request The model creation request containing all necessary details
     * @return Either.Right with the created LLMModelDetails on success, or Either.Left with RepositoryError on failure
     */
    suspend fun addModel(request: AddModelRequest): Either<RepositoryError, LLMModelDetails>

    /**
     * Updates an existing LLM model configuration.
     *
     * Upon successful update, the modified model's details replace the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param model The updated model object with the same ID as the existing model
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateModel(model: LLMModel): Either<RepositoryError, Unit>

    /**
     * Deletes an LLM model configuration.
     *
     * Upon successful deletion, the model's details are automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param modelId The unique identifier of the model to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteModel(modelId: Long): Either<RepositoryError, Unit>

    /**
     * Checks the API key status for a specific model's provider.
     *
     * This method provides information about whether the provider associated with
     * the specified model has a valid API key configured.
     *
     * @param modelId The unique identifier of the model whose provider's API key status to check
     * @return Either.Right with ApiKeyStatusResponse on success, or Either.Left with RepositoryError on failure
     */
    suspend fun getModelApiKeyStatus(modelId: Long): Either<RepositoryError, ApiKeyStatusResponse>

    /**
     * Makes a model publicly accessible by granting READ access to the "All Users" group.
     *
     * Upon successful operation, the internal StateFlow of model details is updated.
     *
     * @param modelId The ID of the model to make public.
     * @return Either.Right with [Unit] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun makeModelPublic(modelId: Long): Either<RepositoryError, Unit>

    /**
     * Makes a model private by revoking all access from the "All Users" group.
     *
     * Upon successful operation, the internal StateFlow of model details is updated.
     *
     * @param modelId The ID of the model to make private.
     * @return Either.Right with [Unit] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun makeModelPrivate(modelId: Long): Either<RepositoryError, Unit>

    /**
     * Grants access to a model for a specific user group with the specified access mode.
     *
     * Upon successful operation, the internal StateFlow of model details is updated.
     *
     * @param modelId The ID of the model.
     * @param request The grant access request containing groupId and accessMode.
     * @return Either.Right with [LLMModelDetails] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun grantModelAccess(
        modelId: Long,
        request: GrantAccessRequest
    ): Either<RepositoryError, LLMModelDetails>

    /**
     * Revokes access to a model from a specific user group for the specified access mode.
     *
     * Upon successful operation, the internal StateFlow of model details is updated.
     *
     * @param modelId The ID of the model.
     * @param request The revoke access request containing groupId and accessMode.
     * @return Either.Right with [LLMModelDetails] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun revokeModelAccess(
        modelId: Long,
        request: RevokeAccessRequest
    ): Either<RepositoryError, LLMModelDetails>
}
