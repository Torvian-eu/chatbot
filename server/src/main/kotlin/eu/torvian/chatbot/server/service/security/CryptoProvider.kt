package eu.torvian.chatbot.server.service.security

/**
 * Interface for cryptographic operations.
 *
 * This interface defines the contract for cryptographic providers that implement
 * envelope encryption, where data is encrypted with a Data Encryption Key (DEK)
 * and the DEK is encrypted with a Key Encryption Key (KEK).
 *
 * All methods in this interface work with Base64-encoded strings to hide
 * implementation details and decouple the rest of the system from crypto-specific types.
 */
interface CryptoProvider {
    /**
     * Generates a new random Data Encryption Key (DEK).
     *
     * @return A Base64-encoded string representation of the DEK.
     */
    fun generateDEK(): String

    /**
     * Encrypts data using the provided DEK.
     *
     * @param plainText The plaintext data to encrypt.
     * @param dek The Base64-encoded DEK to use for encryption.
     * @return A Base64-encoded string containing the encrypted data.
     */
    fun encryptData(plainText: String, dek: String): String

    /**
     * Decrypts data using the provided DEK.
     *
     * @param cipherText The Base64-encoded encrypted data.
     * @param dek The Base64-encoded DEK to use for decryption.
     * @return The decrypted plaintext data.
     */
    fun decryptData(cipherText: String, dek: String): String

    /**
     * Encrypts (wraps) a DEK using the KEK.
     *
     * @param dek The Base64-encoded DEK to encrypt.
     * @return A Base64-encoded string containing the encrypted DEK.
     */
    fun wrapDEK(dek: String): String

    /**
     * Decrypts (unwraps) a DEK using the KEK.
     *
     * @param wrappedDek The Base64-encoded encrypted DEK.
     * @return The decrypted DEK as a Base64-encoded string.
     */
    fun unwrapDEK(wrappedDek: String): String

    /**
     * Gets the current version of the KEK.
     * This can be used to track which version of the KEK was used for encryption.
     *
     * @return The current KEK version.
     */
    fun getKeyVersion(): Int
}