package eu.torvian.chatbot.worker.setup

/**
 * Logical errors that can occur while loading the worker private key at runtime.
 *
 * These errors are raised by [PrivateKeyProvider] implementations when the private key
 * cannot be obtained from any configured source.
 */
sealed interface PrivateKeyLoadError {

    /**
     * Indicates that no private key could be obtained.
     *
     * @property reason Human-readable explanation of why the key is unavailable.
     */
    data class Unavailable(val reason: String) : PrivateKeyLoadError

    /**
     * Indicates that reading the secrets file failed after the environment variable
     * was absent or blank.
     *
     * @property path File path that failed to read.
     * @property reason Human-readable I/O or parse failure reason.
     */
    data class SecretsReadFailed(val path: String, val reason: String) : PrivateKeyLoadError
}
