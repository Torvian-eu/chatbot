package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.error.LLMProviderError

/**
 * Repository interface for managing LLM provider configurations in the database.
 *
 * Defines operations for managing LLM provider metadata including their API key references,
 * endpoints, and configuration details.
 */
interface LLMProviderDao {
    /**
     * Retrieves all LLM provider configurations.
     *
     * @return List of all LLM providers in the system.
     */
    suspend fun getAllProviders(): List<LLMProvider>

    /**
     * Retrieves a single LLM provider configuration by its unique identifier.
     *
     * @param id The unique identifier of the LLM provider to retrieve.
     * @return Either an [LLMProviderError.LLMProviderNotFound] if not found, or the [LLMProvider].
     */
    suspend fun getProviderById(id: Long): Either<LLMProviderError.LLMProviderNotFound, LLMProvider>

    /**
     * Inserts a new LLM provider configuration into the database.
     *
     * @param apiKeyId Reference to the credential storage system for the API key (null for local providers).
     * @param name The display name for the provider.
     * @param description The description for the provider.
     * @param baseUrl The base URL for the LLM API endpoint.
     * @param type The type of LLM provider.
     * @return Either an [LLMProviderError.ApiKeyAlreadyInUse] if the API key is already used,
     *         or the newly created [LLMProvider].
     */
    suspend fun insertProvider(
        apiKeyId: String?,
        name: String,
        description: String,
        baseUrl: String,
        type: LLMProviderType
    ): Either<LLMProviderError.ApiKeyAlreadyInUse, LLMProvider>

    /**
     * Updates an existing LLM provider configuration.
     *
     * @param provider The LLM provider with updated values. The ID must match an existing provider.
     * @return Either an [LLMProviderError] or [Unit] on success.
     */
    suspend fun updateProvider(provider: LLMProvider): Either<LLMProviderError, Unit>

    /**
     * Deletes an LLM provider configuration from the database.
     * Note: This should check for dependent models before deletion.
     *
     * @param id The unique identifier of the LLM provider to delete.
     * @return Either an [LLMProviderError.LLMProviderNotFound] if not found, or [Unit] on success.
     */
    suspend fun deleteProvider(id: Long): Either<LLMProviderError.LLMProviderNotFound, Unit>
}
