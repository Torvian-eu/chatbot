package eu.torvian.chatbot.common.security

/**
 * Configuration for encryption-related settings.
 *
 * This class centralizes encryption configuration and provides parameters for
 * envelope encryption. It supports key rotation by managing multiple master keys.
 *
 * @property masterKeys A map of KEK versions to their Base64-encoded key values.
 * @property keyVersion The version of the key in [masterKeys] to be used for all new
 *                      encryption operations. This is the "current" or "primary" key.
 * @property algorithm The encryption algorithm to use (e.g., "AES"). Defaults to AES in provider.
 * @property transformation The cipher transformation to use (e.g., "AES/CBC/PKCS5Padding"). Defaults to AES/CBC/PKCS5Padding in provider.
 * @property keySizeBits The size of the keys in bits (e.g., 256). Defaults to 256 in provider.
 */
data class EncryptionConfig(
    val masterKeys: Map<Int, String>,
    val keyVersion: Int,
    val algorithm: String? = null,
    val transformation: String? = null,
    val keySizeBits: Int? = null
)
