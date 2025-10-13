package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.llm.AddProviderRequest
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.api.llm.UpdateProviderCredentialRequest
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails

/**
 * Frontend API interface for interacting with LLM Provider-related endpoints.
 *
 * This interface defines the operations for managing LLM provider configurations
 * and retrieving associated models. Implementations use the internal HTTP API.
 * All methods are suspend functions and return [Either<ApiResourceError, T>].
 */
interface ProviderApi {
    /**
     * Retrieves a list of all configured LLM providers.
     *
     * Corresponds to `GET /api/v1/providers`.
     * (E4.S9)
     *
     * @return [Either.Right] containing a list of [LLMProvider] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getAllProviders(): Either<ApiResourceError, List<LLMProvider>>

    /**
     * Adds a new LLM provider configuration.
     *
     * Corresponds to `POST /api/v1/providers`.
     * (E4.S8)
     *
     * @param request The request body with provider details, including optional credential.
     * @return [Either.Right] containing the newly created [LLMProvider] object (without raw credential) on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun addProvider(request: AddProviderRequest): Either<ApiResourceError, LLMProvider>

    /**
     * Retrieves details for a specific LLM provider configuration.
     *
     * Corresponds to `GET /api/v1/providers/{providerId}`.
     *
     * @param providerId The ID of the provider to retrieve.
     * @return [Either.Right] containing the requested [LLMProvider] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getProviderById(providerId: Long): Either<ApiResourceError, LLMProvider>

    /**
     * Updates details for a specific LLM provider configuration.
     * Does NOT update the credential (use [updateProviderCredential]).
     *
     * Corresponds to `PUT /api/v1/providers/{providerId}`.
     * (E4.S10)
     *
     * @param provider The [LLMProvider] object with updated details. The ID must match the path.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, invalid input).
     */
    suspend fun updateProvider(provider: LLMProvider): Either<ApiResourceError, Unit>

    /**
     * Deletes an LLM provider configuration.
     * Will fail if models are still linked to this provider.
     *
     * Corresponds to `DELETE /api/v1/providers/{providerId}`.
     * (E4.S11)
     *
     * @param providerId The ID of the provider to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, resource in use).
     */
    suspend fun deleteProvider(providerId: Long): Either<ApiResourceError, Unit>

    /**
     * Updates the securely stored API key credential for a specific LLM provider.
     *
     * Corresponds to `PUT /api/v1/providers/{providerId}/credential`.
     * (E4.S12)
     *
     * @param providerId The ID of the provider.
     * @param request The request body containing the new credential string (or null to remove).
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, internal error).
     */
    suspend fun updateProviderCredential(
        providerId: Long,
        request: UpdateProviderCredentialRequest
    ): Either<ApiResourceError, Unit>

    /**
     * Retrieves a list of LLM model configurations linked to a specific provider.
     *
     * Corresponds to `GET /api/v1/providers/{providerId}/models`.
     *
     * @param providerId The ID of the provider.
     * @return [Either.Right] containing a list of [LLMModel] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., provider not found).
     */
    suspend fun getModelsByProviderId(providerId: Long): Either<ApiResourceError, List<LLMModel>>

    /**
     * Retrieves detailed information about a provider, including owner and access list.
     *
     * Corresponds to `GET /api/v1/providers/{providerId}/details`.
     *
     * @param providerId The ID of the provider
     * @return Either.Right with [LLMProviderDetails] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun getProviderDetails(providerId: Long): Either<ApiResourceError, LLMProviderDetails>

    /**
     * Retrieves detailed information about all providers accessible to the current user.
     *
     * Corresponds to `GET /api/v1/providers/details`.
     *
     * @return Either.Right with list of [LLMProviderDetails] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun getAllProviderDetails(): Either<ApiResourceError, List<LLMProviderDetails>>

    /**
     * Makes a provider publicly accessible by granting READ access to the "All Users" group.
     *
     * Corresponds to `POST /api/v1/providers/{providerId}/make-public`.
     *
     * @param providerId The ID of the provider to make public
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun makeProviderPublic(providerId: Long): Either<ApiResourceError, Unit>

    /**
     * Makes a provider private by revoking all access from the "All Users" group.
     *
     * Corresponds to `POST /api/v1/providers/{providerId}/make-private`.
     *
     * @param providerId The ID of the provider to make private
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun makeProviderPrivate(providerId: Long): Either<ApiResourceError, Unit>

    /**
     * Grants access to a provider for a specific user group with the specified access mode.
     *
     * Corresponds to `POST /api/v1/providers/{providerId}/access`.
     *
     * @param providerId The ID of the provider
     * @param request The grant access request containing groupId and accessMode
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun grantProviderAccess(
        providerId: Long,
        request: GrantAccessRequest
    ): Either<ApiResourceError, Unit>

    /**
     * Revokes access to a provider from a specific user group for the specified access mode.
     *
     * Corresponds to `DELETE /api/v1/providers/{providerId}/access`.
     *
     * @param providerId The ID of the provider
     * @param request The revoke access request containing groupId and accessMode
     * @return Either.Right with [Unit] on success, or Either.Left with [ApiResourceError] on failure
     */
    suspend fun revokeProviderAccess(
        providerId: Long,
        request: RevokeAccessRequest
    ): Either<ApiResourceError, Unit>
}