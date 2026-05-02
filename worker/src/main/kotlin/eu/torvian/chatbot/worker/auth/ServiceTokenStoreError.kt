package eu.torvian.chatbot.worker.auth

/**
 * Logical errors for persisted access-token storage.
 */
sealed interface ServiceTokenStoreError {
    /**
     * Indicates that the token cache could not be read from disk.
     *
     * @property path File path that failed to read.
     * @property reason Human-readable I/O failure reason.
     */
    data class TokenCacheReadFailed(val path: String, val reason: String) : ServiceTokenStoreError

    /**
     * Indicates that the token cache file was readable but did not contain valid token JSON.
     *
     * @property path File path that contained invalid token data.
     * @property reason Human-readable parse failure reason.
     */
    data class TokenCacheCorrupt(val path: String, val reason: String) : ServiceTokenStoreError

    /**
     * Indicates that the token cache could not be written to disk.
     *
     * @property path File path that failed to write.
     * @property reason Human-readable write failure reason.
     */
    data class TokenCacheWriteFailed(val path: String, val reason: String) : ServiceTokenStoreError

    /**
     * Indicates that the token cache could not be deleted from disk.
     *
     * @property path File path that failed to delete.
     * @property reason Human-readable delete failure reason.
     */
    data class TokenCacheDeleteFailed(val path: String, val reason: String) : ServiceTokenStoreError
}

