package eu.torvian.chatbot.common.security

/**
 * Sealed class representing various crypto operation errors.
 * These are logical errors that can occur during cryptographic operations.
 */
sealed class CryptoError {
    /**
     * A comprehensive, technical message describing the error, suitable for logging and debugging.
     * This message often includes details from the underlying [cause] if available.
     */
    abstract val message: String

    /**
     * The underlying [Throwable] that caused this error, if applicable.
     */
    abstract val cause: Throwable?

    /**
     * Error when a provided key is invalid (wrong format, size, etc.)
     */
    data class InvalidKey(
        override val message: String,
        override val cause: Throwable? = null
    ) : CryptoError()

    /**
     * Error when ciphertext is malformed or corrupted
     */
    data class InvalidCiphertext(
        override val message: String,
        override val cause: Throwable? = null
    ) : CryptoError()

    /**
     * Error when a requested key version is not available
     */
    data class KeyVersionNotFound(val version: Int) : CryptoError() {
        override val message: String = "Key version $version not found."
        override val cause: Throwable? = null
    }

    /**
     * Error when encryption configuration is invalid
     */
    data class ConfigurationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : CryptoError()

    /**
     * Error during encryption operation
     */
    data class EncryptionError(
        override val message: String,
        override val cause: Throwable? = null
    ) : CryptoError()

    /**
     * Error during decryption operation
     */
    data class DecryptionError(
        override val message: String,
        override val cause: Throwable? = null
    ) : CryptoError()

    /**
     * Error during key generation
     */
    data class KeyGenerationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : CryptoError()
}
