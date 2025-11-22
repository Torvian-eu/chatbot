package eu.torvian.chatbot.app.database.dao.error

/**
 * Represents possible errors that can occur during LocalMCPServer data operations
 * on the client side.
 */
sealed interface LocalMCPServerError {
    /**
     * Indicates that environment variable encryption failed.
     *
     * @param message Error message describing the failure
     * @param cause The underlying exception
     */
    data class EncryptionFailed(
        val message: String,
        val cause: Throwable? = null
    ) : LocalMCPServerError

    /**
     * Indicates that environment variable decryption failed.
     *
     * @param secretId The encrypted secret ID that failed to decrypt
     * @param message Error message describing the failure
     * @param cause The underlying exception
     */
    data class DecryptionFailed(
        val secretId: Long,
        val message: String,
        val cause: Throwable? = null
    ) : LocalMCPServerError
}

/**
 * Error type for LocalMCPServer update operations.
 */
sealed interface UpdateLocalMCPServerError {
    /**
     * The server with the specified ID was not found.
     */
    data class NotFound(val id: Long) : UpdateLocalMCPServerError

    /**
     * The update would create a duplicate name for this user.
     */
    data class DuplicateName(val name: String, val userId: Long) : UpdateLocalMCPServerError

    /**
     * Environment variable encryption failed during update.
     */
    data class EncryptionFailed(
        val message: String,
        val cause: Throwable? = null
    ) : UpdateLocalMCPServerError
}

/**
 * Error type for LocalMCPServer deletion operations.
 */
sealed interface DeleteLocalMCPServerError {
    /**
     * The server with the specified ID was not found.
     */
    data class NotFound(val id: Long) : DeleteLocalMCPServerError

    /**
     * Failed to delete associated encrypted secret.
     * This is a critical error since the secret is stored exclusively for this server.
     */
    data class SecretCleanupFailed(
        val secretId: Long,
        val message: String,
        val cause: Throwable? = null
    ) : DeleteLocalMCPServerError
}

/**
 * Error type for LocalMCPServer retrieval operations (getById).
 * This consolidates the different failure modes of getById into a single error type.
 */
sealed interface GetLocalMCPServerError {
    /**
     * The server with the specified ID was not found.
     */
    data class NotFound(val id: Long) : GetLocalMCPServerError

    /**
     * Environment variable decryption failed.
     */
    data class DecryptionFailed(
        val secretId: Long,
        val message: String,
        val cause: Throwable? = null
    ) : GetLocalMCPServerError
}

