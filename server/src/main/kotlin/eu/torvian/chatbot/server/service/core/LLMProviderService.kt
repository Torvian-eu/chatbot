package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.core.error.provider.*

/**
 * Service interface for managing LLM Providers.
 */
interface LLMProviderService {
    /**
     * Retrieves all LLM provider configurations.
     *
     * @return List of all LLM providers in the system.
     */
    suspend fun getAllProviders(): List<LLMProvider>


    /**
     * Retrieves all LLM provider configurations accessible by the specified user.
     * @param userId The ID of the user requesting the providers.
     * @param accessMode The access mode to query (e.g., "read", "write").
     * @return List of all LLM providers accessible by the user.
     */
    suspend fun getAllAccessibleProviders(userId: Long, accessMode: AccessMode): List<LLMProvider>

    /**
     * Retrieves a single LLM provider configuration by its unique identifier.
     *
     * @param id The unique identifier of the provider to retrieve.
     * @return [Either] a [GetProviderError], or the [LLMProvider].
     */
    suspend fun getProviderById(id: Long): Either<GetProviderError, LLMProvider>

    /**
     * Adds a new LLM provider configuration, owned by the specified user.
     * Creates a secure credential entry and associates it with the provided metadata.
     *
     * @param ownerId The ID of the user creating the provider.
     * @param name The display name for the provider.
     * @param description The description for the provider.
     * @param baseUrl The base URL for the LLM API endpoint.
     * @param type The type of LLM provider.
     * @param credential The actual API key credential to store securely (null for local providers).
     * @return Either an [AddProviderError], or the newly created [LLMProvider].
     */
    suspend fun addProvider(ownerId: Long, name: String, description: String, baseUrl: String, type: LLMProviderType, credential: String?): Either<AddProviderError, LLMProvider>

    /**
     * Updates an existing LLM provider configuration.
     * Note: This updates all metadata. To update the credential, delete and recreate the provider.
     *
     * @param provider The LLMProvider object containing the updated values. The ID must match an existing provider.
     * @return Either an [UpdateProviderError] or Unit if successful.
     */
    suspend fun updateProvider(provider: LLMProvider): Either<UpdateProviderError, Unit>

    /**
     * Deletes an LLM provider configuration and its associated credential.
     * Checks if the provider is still in use by any models before deletion.
     *
     * @param id The ID of the provider to delete.
     * @return Either a [DeleteProviderError], or Unit if successful.
     */
    suspend fun deleteProvider(id: Long): Either<DeleteProviderError, Unit>

    /**
     * Updates the API key credential for an existing LLM provider.
     * This replaces the old credential with a new one and updates the provider's apiKeyId reference.
     *
     * @param providerId The ID of the provider to update.
     * @param newCredential The new API key credential to store securely. (null to remove the credential)
     * @return Either an [UpdateProviderCredentialError], or Unit if successful.
     */
    suspend fun updateProviderCredential(providerId: Long, newCredential: String?): Either<UpdateProviderCredentialError, Unit>
}
