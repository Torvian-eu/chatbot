package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.api.llm.DiscoveredProviderModel
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
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
     * Reactive stream of all LLM provider configurations, including ownership and access details.
     *
     * This StateFlow provides real-time updates whenever the provider data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all providers wrapped in DataState
     */
    val providersDetails: StateFlow<DataState<RepositoryError, List<LLMProviderDetails>>>

    /**
     * Reactive stream of all LLM provider configurations.
     *
     * This one should be used instead of providersDetails() when access details are not needed.
     *
     * @return StateFlow containing the current state of all providers wrapped in DataState
     */
    val providers: StateFlow<DataState<RepositoryError, List<LLMProvider>>>

    /**
     * Loads all LLM provider configurations from the backend, including ownership and access details.
     *
     * This operation fetches the latest provider data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadProvidersDetails(): Either<RepositoryError, Unit>

    /**
     * Loads all LLM provider configurations from the backend.
     *
     * This one should be used instead of loadProvidersDetails() when access details are not needed.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadProviders(): Either<RepositoryError, Unit>

    /**
     * Loads detailed information about a specific LLM provider, including owner and access list.
     *
     * This operation fetches the latest provider details and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @param providerId The unique identifier of the provider to load
     * @return Either.Right with LLMProviderDetails on success, or Either.Left with RepositoryError on failure
     */
    suspend fun loadProviderDetails(providerId: Long): Either<RepositoryError, LLMProviderDetails>

    /**
     * Adds a new LLM provider configuration.
     *
     * Upon successful creation, the new provider's details are automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param name The display name for the provider
     * @param description Description providing additional context about the provider
     * @param baseUrl The base URL for the LLM API endpoint
     * @param type The type of LLM provider
     * @param credential The API key credential to store securely (null for local providers)
     * @return Either.Right with the created LLMProviderDetails on success, or Either.Left with RepositoryError on failure
     */
    suspend fun addProvider(
        name: String,
        description: String,
        baseUrl: String,
        type: LLMProviderType,
        credential: String? = null
    ): Either<RepositoryError, LLMProviderDetails>

    /**
     * Tests provider connectivity using unsaved form data.
     *
     * @param baseUrl The provider base URL to test.
     * @param type The provider type to test.
     * @param credential Optional credential to use for authentication.
     * @return Either.Right with discovered models on success, or Either.Left with RepositoryError on failure.
     */
    suspend fun testProviderConnection(
        baseUrl: String,
        type: LLMProviderType,
        credential: String? = null
    ): Either<RepositoryError, List<DiscoveredProviderModel>>

    /**
     * Discovers remote models for an existing provider configuration.
     *
     * @param providerId The provider ID to query.
     * @return Either.Right with discovered models on success, or Either.Left with RepositoryError on failure.
     */
    suspend fun discoverProviderModels(
        providerId: Long
    ): Either<RepositoryError, List<DiscoveredProviderModel>>

    /**
     * Updates an existing LLM provider configuration.
     *
     * This method updates the provider's metadata but does NOT update credentials.
     * Use updateProviderCredential() for credential updates.
     *
     * @param provider The updated provider object with the same ID as the existing provider
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateProvider(provider: LLMProvider): Either<RepositoryError, Unit>

    /**
     * Deletes an LLM provider configuration.
     *
     * This operation will fail if there are still models linked to this provider.
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
     * @param credential The new credential or null to remove
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateProviderCredential(
        providerId: Long,
        credential: String?
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

    /**
     * Makes a provider publicly accessible by granting READ access to the "All Users" group.
     *
     * Upon successful operation, the internal StateFlow of provider details is updated.
     *
     * @param providerId The ID of the provider to make public.
     * @return Either.Right with [Unit] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun makeProviderPublic(providerId: Long): Either<RepositoryError, Unit>

    /**
     * Makes a provider private by revoking all access from the "All Users" group.
     *
     * Upon successful operation, the internal StateFlow of provider details is updated.
     *
     * @param providerId The ID of the provider to make private.
     * @return Either.Right with [Unit] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun makeProviderPrivate(providerId: Long): Either<RepositoryError, Unit>

    /**
     * Grants access to a provider for a specific user group with the specified access mode.
     *
     * Upon successful operation, the internal StateFlow of provider details is updated.
     *
     * @param providerId The ID of the provider.
     * @param groupId The ID of the user group.
     * @param accessMode The access mode to grant.
     * @return Either.Right with [LLMProviderDetails] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun grantProviderAccess(
        providerId: Long,
        groupId: Long,
        accessMode: String
    ): Either<RepositoryError, LLMProviderDetails>

    /**
     * Revokes access to a provider from a specific user group for the specified access mode.
     *
     * Upon successful operation, the internal StateFlow of provider details is updated.
     *
     * @param providerId The ID of the provider.
     * @param groupId The ID of the user group.
     * @param accessMode The access mode to revoke.
     * @return Either.Right with [LLMProviderDetails] on success, or Either.Left with [RepositoryError](psi_element://eu.torvian.chatbot.app.repository.RepositoryError) on failure.
     */
    suspend fun revokeProviderAccess(
        providerId: Long,
        groupId: Long,
        accessMode: String
    ): Either<RepositoryError, LLMProviderDetails>
}
