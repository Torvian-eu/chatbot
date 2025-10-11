package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.core.error.access.GrantResourceAccessError
import eu.torvian.chatbot.server.service.core.error.access.MakeResourcePrivateError
import eu.torvian.chatbot.server.service.core.error.access.MakeResourcePublicError
import eu.torvian.chatbot.server.service.core.error.access.RevokeResourceAccessError
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
    suspend fun addProvider(
        ownerId: Long,
        name: String,
        description: String,
        baseUrl: String,
        type: LLMProviderType,
        credential: String?
    ): Either<AddProviderError, LLMProvider>

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
    suspend fun updateProviderCredential(
        providerId: Long,
        newCredential: String?
    ): Either<UpdateProviderCredentialError, Unit>

    // --- Access Management ---

    /**
     * Grants access to a provider for a specific user group with the specified access mode.
     *
     * @param providerId The ID of the provider to grant access to
     * @param groupId The ID of the user group to grant access
     * @param accessMode The access mode to grant (e.g., AccessMode.READ, AccessMode.WRITE)
     * @return Either [GrantResourceAccessError] or Unit on success
     */
    suspend fun grantProviderAccess(
        providerId: Long,
        groupId: Long,
        accessMode: AccessMode
    ): Either<GrantResourceAccessError, Unit>

    /**
     * Revokes access to a provider from a specific user group for the specified access mode.
     *
     * @param providerId The ID of the provider to revoke access from
     * @param groupId The ID of the user group to revoke access from
     * @param accessMode The access mode to revoke
     * @return Either [RevokeResourceAccessError] or Unit on success
     */
    suspend fun revokeProviderAccess(
        providerId: Long,
        groupId: Long,
        accessMode: AccessMode
    ): Either<RevokeResourceAccessError, Unit>

    /**
     * Retrieves provider details, including the owner and all groups with access.
     *
     * @param providerId The ID of the provider to query
     * @return Either [GetProviderError] or [LLMProviderDetails]
     */
    suspend fun getProviderDetails(providerId: Long): Either<GetProviderError, LLMProviderDetails>

    // --- Convenience Methods ---

    /**
     * Makes a provider publicly accessible by granting READ access to the "All Users" group.
     *
     * This is a convenience method that internally grants READ access to the special
     * "All Users" group, making the provider visible to all users in the system.
     *
     * @param providerId The ID of the provider to make public
     * @return Either [MakeResourcePublicError] or Unit on success
     */
    suspend fun makeProviderPublic(providerId: Long): Either<MakeResourcePublicError, Unit>

    /**
     * Makes a provider private by revoking all access from the "All Users" group.
     *
     * This is a convenience method that removes all access from
     * the "All Users" group, making the provider accessible only to users with
     * explicit group access or the owner.
     *
     * @param providerId The ID of the provider to make private
     * @return Either [MakeResourcePrivateError] or Unit on success
     */
    suspend fun makeProviderPrivate(providerId: Long): Either<MakeResourcePrivateError, Unit>
}
