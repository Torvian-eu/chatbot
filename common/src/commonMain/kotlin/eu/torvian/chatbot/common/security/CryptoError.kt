package eu.torvian.chatbot.common.security

/**
 * Sealed class representing various crypto operation errors.
 * These are logical errors that can occur during cryptographic operations.
 */
sealed class CryptoError {
    /**
     * Error when a provided key is invalid (wrong format, size, etc.)
     */
    data class InvalidKey(val message: String) : CryptoError()

    /**
     * Error when ciphertext is malformed or corrupted
     */
    data class InvalidCiphertext(val message: String) : CryptoError()

    /**
     * Error when a requested key version is not available
     */
    data class KeyVersionNotFound(val version: Int) : CryptoError()

    /**
     * Error when encryption configuration is invalid
     */
    data class ConfigurationError(val message: String) : CryptoError()

    /**
     * Error during encryption operation
     */
    data class EncryptionError(val message: String) : CryptoError()

    /**
     * Error during decryption operation
     */
    data class DecryptionError(val message: String) : CryptoError()

    /**
     * Error during key generation
     */
    data class KeyGenerationError(val message: String) : CryptoError()
}
