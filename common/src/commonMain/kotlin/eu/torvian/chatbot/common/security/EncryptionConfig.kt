package eu.torvian.chatbot.common.security

/**
 * Configuration for encryption operations.
 *
 * This class centralizes encryption configuration and provides parameters for
 * envelope encryption. It supports key rotation by managing multiple master keys.
 *
 * @property masterKeys Map of key version to Base64-encoded master keys
 * @property keyVersion Current active key version
 * @property algorithm Encryption algorithm (defaults to AES)
 * @property transformation Cipher transformation (defaults to AES/CBC/PKCS5Padding)
 * @property keySizeBits Key size in bits (defaults to 256)
 */
data class EncryptionConfig(
    val masterKeys: Map<Int, String>,
    val keyVersion: Int,
    val algorithm: String? = null,
    val transformation: String? = null,
    val keySizeBits: Int? = null
)
