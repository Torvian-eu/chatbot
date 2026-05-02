package eu.torvian.chatbot.worker.setup

/**
 * Logical errors that can occur while reading or writing the secrets store.
 */
sealed interface SecretsStoreError {
    /**
     * The secrets file was not found at the expected path.
     *
     * @property path Missing file path.
     */
    data class NotFound(val path: String) : SecretsStoreError

    /**
     * The secrets file could not be read from disk.
     *
     * @property path File path that failed to read.
     * @property reason Human-readable I/O failure reason.
     */
    data class ReadFailed(val path: String, val reason: String) : SecretsStoreError

    /**
     * The secrets file was present but invalid (for example malformed JSON or unexpected schema).
     *
     * @property path File path that was read.
     * @property reason Human-readable validation failure detail.
     */
    data class Invalid(val path: String, val reason: String) : SecretsStoreError

    /**
     * The secrets file could not be written to disk.
     *
     * @property path File path that failed to write.
     * @property reason Human-readable I/O failure reason.
     */
    data class WriteFailed(val path: String, val reason: String) : SecretsStoreError
}
