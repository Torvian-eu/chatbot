package eu.torvian.chatbot.server.service.security

/**
* Interface for managing sensitive credentials (like API keys) using a secure storage mechanism.
*
* Implements [E5.S1 - Implement Secure API Key Storage].
*
* Implementations could use OS keystores, database-backed encryption, or external secret managers.
*
* @property storeCredential Stores a credential securely using a unique alias.
* @property getCredential Retrieves a securely stored credential using its alias/reference ID.
* @property deleteCredential Deletes a securely stored credential using its alias/reference ID.
*/
interface CredentialManager {
    /**
     * Stores a credential securely using a unique alias.
     *
     * The implementation should generate a unique, opaque alias or accept one provided by the caller.
     * This alias (reference ID) is what is stored in the database (e.g., in `LLMModels`), not the raw credential.
     * The actual storage mechanism interacts with the underlying secure storage API (e.g., database, OS API).
     *
     * Implements part of E5.S1. Must be [suspend] as it performs blocking I/O with the storage.
     *
     * @param credential The sensitive string (e.g., API key) to store.
     * @return A string alias/reference ID that can be used to retrieve the credential,
     *         or null if storage failed. This ID is what is stored in entities referencing the secret.
     */
    suspend fun storeCredential(credential: String): String?
    
    /**
     * Retrieves a securely stored credential using its alias/reference ID.
     *
     * Implements part of [E5.S2 - Securely Retrieve API Key]. Must be [suspend] as it performs blocking I/O with the storage.
     *
     * @param alias The alias/reference ID returned by [storeCredential].
     * @return The decrypted credential string, or null if not found or retrieval failed.
     */
    suspend fun getCredential(alias: String): String?
    
    /**
     * Deletes a securely stored credential using its alias/reference ID.
     *
     * Implements part of [E5.S3 - Securely Delete API Key]. Must be [suspend] as it performs blocking I/O with the storage.
     *
     * @param alias The alias/reference ID of the credential to delete.
     * @return True if deletion was successful (or the credential wasn't found, which is a success state for deletion), false otherwise on failure.
     */
    suspend fun deleteCredential(alias: String): Boolean
}