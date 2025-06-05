package eu.torvian.chatbot.server.domain.security

/**
 * Represents an encrypted secret using envelope encryption.
 *
 * Envelope encryption is a two-layer encryption approach:
 * 1. The secret is encrypted with a Data Encryption Key (DEK)
 * 2. The DEK is encrypted with a Key Encryption Key (KEK)
 *
 * This class holds all the components needed to decrypt the secret:
 * - The encrypted secret itself
 * - The encrypted DEK
 * - The version of the KEK used to encrypt the DEK
 *
 * @property encryptedSecret The secret encrypted with the DEK, Base64 encoded
 * @property encryptedDEK The DEK encrypted with the KEK, Base64 encoded
 * @property keyVersion The version of the KEK used for encryption
 */
data class EncryptedSecret(
    val encryptedSecret: String,
    val encryptedDEK: String,
    val keyVersion: Int
)