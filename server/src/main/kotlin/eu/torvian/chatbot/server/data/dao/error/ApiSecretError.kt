package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during ApiSecret data operations.
 */
sealed interface ApiSecretError {
    /**
     * Indicates that a secret with the specified alias was not found.
     */
    data class SecretNotFound(val alias: String) : ApiSecretError

    /**
     * Indicates that a secret with the specified alias already exists.
     */
    data class SecretAlreadyExists(val alias: String) : ApiSecretError
}