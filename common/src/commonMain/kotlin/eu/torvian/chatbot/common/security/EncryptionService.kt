package eu.torvian.chatbot.common.security

import arrow.core.Either
import arrow.core.raise.either

/**
 *   Service for handling encryption operations using envelope encryption.
 *
 *   This service centralizes all encryption operations and delegates to a
 *   [CryptoProvider] implementation for the actual cryptographic operations.
 *
 *   Envelope encryption is a two-layer encryption approach:
 *   Data is encrypted with a Data Encryption Key (DEK)
 *   The DEK is encrypted with a Key Encryption Key (KEK)
 *
 *   This service provides a high-level API that works with strings and the
 *   [EncryptedSecret] data class, hiding the implementation details of
 *   the cryptographic operations.
 *
 *   @property cryptoProvider The provider of cryptographic operations
 *
 */
class EncryptionService(private val cryptoProvider: CryptoProvider) {

    /**
     * Encrypts a secret using envelope encryption.
     *
     * This method:
     * 1. Generates a new Data Encryption Key (DEK)
     * 2. Encrypts the secret with the DEK
     * 3. Encrypts the DEK with the Key Encryption Key (KEK)
     * 4. Returns an [EncryptedSecret] containing all the necessary information
     *
     * @param plainText The plaintext secret to encrypt.
     * @return Either a CryptoError or an [EncryptedSecret] containing the encrypted secret, encrypted DEK, and key version.
     */
    suspend fun encrypt(plainText: String): Either<CryptoError, EncryptedSecret> = either {
        val dek = cryptoProvider.generateDEK().bind()
        val encryptedSecret = cryptoProvider.encryptData(plainText, dek).bind()
        val wrappedDek = cryptoProvider.wrapDEK(dek).bind()
        val keyVersion = cryptoProvider.getKeyVersion()

        EncryptedSecret(
            encryptedSecret = encryptedSecret,
            encryptedDEK = wrappedDek,
            keyVersion = keyVersion
        )
    }

    /**
     * Decrypts an encrypted secret using envelope encryption.
     *
     * This method:
     * 1. Decrypts the DEK using the KEK of the specified version
     * 2. Decrypts the secret using the DEK
     *
     * @param encrypted The [EncryptedSecret] containing the encrypted secret and DEK details.
     * @return Either a CryptoError or the decrypted plaintext secret.
     */
    suspend fun decrypt(encrypted: EncryptedSecret): Either<CryptoError, String> = either {
        val dek = cryptoProvider.unwrapDEK(encrypted.encryptedDEK, encrypted.keyVersion).bind()
        cryptoProvider.decryptData(encrypted.encryptedSecret, dek).bind()
    }
}
