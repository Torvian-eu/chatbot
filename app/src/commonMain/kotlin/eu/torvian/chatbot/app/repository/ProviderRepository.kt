package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.llm.AddProviderRequest
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.api.llm.UpdateProviderCredentialRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing LLM provider configurations.
 *
 * This repository serves as the single source of truth for provider data in the application,
 * providing reactive data streams through StateFlow and handling all provider-related operations.
 * It abstracts the underlying API layer and provides comprehensive error handling through
 * the RepositoryError hierarchy.
 *
 * The repository maintains an internal cache of provider data and automatically updates
 * all observers when changes occur, ensuring data consistency across the application.
 */
interface ProviderRepository {
    
    /**
     * Reactive stream of all LLM provider configurations.
     * 
     * This StateFlow provides real-time updates whenever the provider data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all providers wrapped in DataState
     */
    val providers: StateFlow<DataState<RepositoryError, List<LLMProvider>>>

    /**
     * Loads all LLM provider configurations from the backend.
     *
     * This operation fetches the latest provider data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadProviders(): Either<RepositoryError, Unit>

    /**
     * Adds a new LLM provider configuration.
     *
     * Upon successful creation, the new provider is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param request The provider creation request containing all necessary details including optional credentials
     * @return Either.Right with the created LLMProvider on success, or Either.Left with RepositoryError on failure
     */
    suspend fun addProvider(request: AddProviderRequest): Either<RepositoryError, LLMProvider>

    /**
     * Retrieves a specific LLM provider configuration by its ID.
     *
     * This method provides direct access to a single provider without affecting
     * the main providers StateFlow.
     *
     * @param providerId The unique identifier of the provider to retrieve
     * @return Either.Right with the LLMProvider on success, or Either.Left with RepositoryError on failure
     */
    suspend fun getProviderById(providerId: Long): Either<RepositoryError, LLMProvider>

    /**
     * Updates an existing LLM provider configuration.
     *
     * This method updates the provider's metadata but does NOT update credentials.
     * Use updateProviderCredential() for credential updates.
     *
     * Upon successful update, the modified provider replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param provider The updated provider object with the same ID as the existing provider
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateProvider(provider: LLMProvider): Either<RepositoryError, Unit>

    /**
     * Deletes an LLM provider configuration.
     *
     * This operation will fail if there are still models linked to this provider.
     * Upon successful deletion, the provider is automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param providerId The unique identifier of the provider to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteProvider(providerId: Long): Either<RepositoryError, Unit>

    /**
     * Updates the securely stored API key credential for a specific LLM provider.
     *
     * This method handles credential updates separately from provider metadata updates
     * to maintain security and separation of concerns.
     *
     * @param providerId The unique identifier of the provider whose credential to update
     * @param request The credential update request containing the new credential or null to remove
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateProviderCredential(
        providerId: Long,
        request: UpdateProviderCredentialRequest
    ): Either<RepositoryError, Unit>

    /**
     * Retrieves all LLM model configurations linked to a specific provider.
     *
     * This method provides access to the models associated with a provider without
     * affecting the main providers StateFlow.
     *
     * @param providerId The unique identifier of the provider whose models to retrieve
     * @return Either.Right with a list of LLMModel on success, or Either.Left with RepositoryError on failure
     */
    suspend fun getModelsByProviderId(providerId: Long): Either<RepositoryError, List<LLMModel>>
}
