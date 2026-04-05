package eu.torvian.chatbot.server.service.security.error

/**
 * Represents possible errors that can occur during credential management operations.
 */
sealed interface CredentialError {
    /**
     * Indicates that a credential with the specified alias was not found.
     */
    data class CredentialNotFound(val alias: String) : CredentialError

    /**
     * Indicates a credential exists but cannot be decrypted.
     */
    data class CredentialDecryptionFailed(val alias: String) : CredentialError
}

