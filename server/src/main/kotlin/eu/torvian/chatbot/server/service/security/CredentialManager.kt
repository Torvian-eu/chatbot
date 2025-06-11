package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.server.service.security.error.CredentialError.CredentialNotFound

/**
 * Interface for managing sensitive credentials (like API keys) using a secure storage mechanism.
 *
 * Implements [E5.S1 - Implement Secure API Key Storage].
 */
interface CredentialManager {
    /**
     * Stores a credential securely using a unique alias.
     *
     * The implementation should generate a unique, opaque alias or accept one provided by the caller.
     * This alias (reference ID) is what is stored in the database (e.g., in `LLMModelTable`), not the raw credential.
     * The actual storage mechanism interacts with the underlying secure storage API (e.g., database, OS API).
     *
     * @param credential The sensitive string (e.g., API key) to store.
     * @return A string alias/reference ID that can be used to retrieve the credential.
     */
    suspend fun storeCredential(credential: String): String

    /**
     * Retrieves a securely stored credential using its alias/reference ID.
     *
     * @param alias The alias/reference to the credential to retrieve.
     * @return Either a [CredentialNotFound] if not found, or the decrypted credential string.
     */
    suspend fun getCredential(alias: String): Either<CredentialNotFound, String>

    /**
     * Deletes a securely stored credential using its alias/reference ID.
     *
     * @param alias The alias/reference ID of the credential to delete.
     * @return Either a [CredentialNotFound] if not found, or [Unit] on success.
     */
    suspend fun deleteCredential(alias: String): Either<CredentialNotFound, Unit>
}