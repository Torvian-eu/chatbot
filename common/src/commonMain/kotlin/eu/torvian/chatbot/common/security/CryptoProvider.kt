package eu.torvian.chatbot.common.security

import arrow.core.Either

/**
 * Interface for cryptographic operations.
 *
 * This interface defines the contract for cryptographic providers that implement
 * envelope encryption, where data is encrypted with a Data Encryption Key (DEK)
 * and the DEK is encrypted with a Key Encryption Key (KEK).
 *
 * All methods in this interface work with Base64-encoded strings to hide
 * implementation details and decouple the rest of the system from crypto-specific types.
 *
 * NOTE: The methods are suspend functions to accommodate asynchronous cryptographic
 * APIs like the Web Crypto API in browsers.
 */
interface CryptoProvider {
    /**
     * Generates a new random Data Encryption Key (DEK).
     *
     * @return Either a CryptoError or a Base64-encoded string representation of the DEK.
     */
    suspend fun generateDEK(): Either<CryptoError, String>

    /**
     * Encrypts data using the provided DEK.
     *
     * @param plainText The plaintext data to encrypt.
     * @param dek The Base64-encoded DEK to use for encryption.
     * @return Either a CryptoError or a Base64-encoded string containing the encrypted data.
     */
    suspend fun encryptData(plainText: String, dek: String): Either<CryptoError, String>

    /**
     * Decrypts data using the provided DEK.
     *
     * @param cipherText The Base64-encoded encrypted data.
     * @param dek The Base64-encoded DEK to use for decryption.
     * @return Either a CryptoError or the decrypted plaintext data.
     */
    suspend fun decryptData(cipherText: String, dek: String): Either<CryptoError, String>

    /**
     * Encrypts (wraps) a DEK using the KEK.
     *
     * @param dek The Base64-encoded DEK to encrypt.
     * @return Either a CryptoError or a Base64-encoded string containing the encrypted DEK.
     */
    suspend fun wrapDEK(dek: String): Either<CryptoError, String>

    /**
     * Decrypts (unwraps) a DEK using the KEK of a specific version.
     *
     * @param wrappedDek The Base64-encoded encrypted DEK.
     * @param kekVersion The version of the KEK that was used to wrap this DEK.
     * @return Either a CryptoError or the decrypted DEK as a Base64-encoded string.
     */
    suspend fun unwrapDEK(wrappedDek: String, kekVersion: Int): Either<CryptoError, String>

    /**
     * Gets the current version of the KEK.
     * This can be used to track which version of the KEK was used for encryption.
     *
     * @return The current KEK version.
     */
    fun getKeyVersion(): Int
}
