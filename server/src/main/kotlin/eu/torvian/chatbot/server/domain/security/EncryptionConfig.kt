package eu.torvian.chatbot.server.domain.security

/**
 * Configuration for encryption-related settings.
 *
 * This class centralizes encryption configuration and provides parameters for
 * encryption operations. It supports the envelope encryption approach
 * where data is encrypted with a Data Encryption Key (DEK) and the DEK is encrypted
 * with a Key Encryption Key (KEK).
 *
 * @property masterKey The Base64-encoded master Key Encryption Key (KEK)
 * @property keyVersion The current version of the KEK
 * @property algorithm The encryption algorithm to use (e.g., "AES"). Defaults to AES in provider.
 * @property transformation The cipher transformation to use (e.g., "AES/CBC/PKCS5Padding"). Defaults to AES/CBC/PKCS5Padding in provider.
 * @property keySizeBits The size of the keys in bits (e.g., 256). Defaults to 256 in provider.
 */
data class EncryptionConfig(
    val masterKey: String, // This needs to be Base64 encoded
    val keyVersion: Int,
    val algorithm: String? = null, // Optional: allow overriding defaults
    val transformation: String? = null, // Optional
    val keySizeBits: Int? = null // Optional
)