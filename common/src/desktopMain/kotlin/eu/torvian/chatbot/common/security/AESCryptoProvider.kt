package eu.torvian.chatbot.common.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM implementation of [CryptoProvider] using AES encryption with key rotation support.
 *
 * This class provides a concrete implementation of envelope encryption using AES:
 * Data is encrypted with a Data Encryption Key (DEK)
 * The DEK is encrypted with a Key Encryption Key (KEK)
 *
 * This implementation supports key rotation by maintaining multiple KEKs indexed by version.
 * New encryptions use the current key version, while decryptions can use any available key version.
 *
 * All methods work with Base64-encoded strings to hide implementation details
 * and decouple the rest of the system from crypto-specific types.
 *
 * @property config The encryption configuration containing multiple versioned keys
 */
class AESCryptoProvider(private val config: EncryptionConfig) : CryptoProvider {

    companion object {
        // Use defaults from config if provided, otherwise use these class defaults
        private const val DEFAULT_ALGORITHM = "AES"
        private const val DEFAULT_TRANSFORMATION = "AES/CBC/PKCS5Padding"
        private const val DEFAULT_KEY_SIZE_BITS = 256
        private const val IV_SIZE = 16
    }

    // Properties derived from config or defaults
    private val algorithm: String = config.algorithm ?: DEFAULT_ALGORITHM
    private val transformation: String = config.transformation ?: DEFAULT_TRANSFORMATION
    private val keySizeBits: Int = config.keySizeBits ?: DEFAULT_KEY_SIZE_BITS

    // Store a map of all KEKs, indexed by version
    private val keks: Map<Int, SecretKey> by lazy {
        config.masterKeys.mapValues { (_, keyString) ->
            val keyBytes = Base64.getDecoder().decode(keyString)
            if (keyBytes.size * 8 != keySizeBits) {
                throw IllegalArgumentException("Master key size does not match the configured key size.")
            }
            SecretKeySpec(keyBytes, algorithm)
        }
    }

    // A specific reference to the current KEK for new encryptions
    private val currentKek: SecretKey by lazy {
        keks[config.keyVersion]
            ?: throw IllegalStateException("Current key version ${config.keyVersion} not found in master keys map.")
    }

    /**
     * Generates a new random Data Encryption Key (DEK).
     *
     * @return A Base64-encoded string representation of the DEK.
     */
    override fun generateDEK(): String {
        val keyGenerator = KeyGenerator.getInstance(algorithm)
        keyGenerator.init(keySizeBits, SecureRandom())
        val dek = keyGenerator.generateKey()
        return Base64.getEncoder().encodeToString(dek.encoded)
    }

    /**
     * Encrypts data using the provided DEK.
     *
     * @param plainText The plaintext data to encrypt.
     * @param dek The Base64-encoded DEK to use for encryption.
     * @return A Base64-encoded string containing the encrypted data.
     */
    override fun encryptData(plainText: String, dek: String): String {
        val dekKey = secretKeyFromBase64(dek)
        val cipher = Cipher.getInstance(transformation)
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, dekKey, IvParameterSpec(iv))
        val encryptedBytes = cipher.doFinal(plainText.toByteArray())
        val combined = iv + encryptedBytes
        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypts data using the provided DEK.
     *
     * @param cipherText The Base64-encoded encrypted data.
     * @param dek The Base64-encoded DEK to use for decryption.
     * @return The decrypted plaintext data.
     */
    override fun decryptData(cipherText: String, dek: String): String {
        val dekKey = secretKeyFromBase64(dek)
        val combined = Base64.getDecoder().decode(cipherText)
        // Ensure combined has enough bytes for IV and at least some data
        if (combined.size < IV_SIZE) {
             throw IllegalArgumentException("Ciphertext is too short to contain IV.")
        }
        val iv = combined.copyOfRange(0, IV_SIZE)
        val encryptedBytes = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, dekKey, IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes)
    }

    /**
     * Encrypts (wraps) a DEK using the current KEK.
     *
     * @param dek The Base64-encoded DEK to encrypt.
     * @return A Base64-encoded string containing the encrypted DEK.
     */
    override fun wrapDEK(dek: String): String {
        val dekKey = secretKeyFromBase64(dek)
        val cipher = Cipher.getInstance(transformation)
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, currentKek, IvParameterSpec(iv)) // CHANGED: Use currentKek
        val encryptedDekBytes = cipher.doFinal(dekKey.encoded)
        val combined = iv + encryptedDekBytes
        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypts (unwraps) a DEK using the KEK of a specific version.
     *
     * @param wrappedDek The Base64-encoded encrypted DEK.
     * @param kekVersion The version of the KEK that was used to wrap this DEK.
     * @return The decrypted DEK as a Base64-encoded string.
     */
    override fun unwrapDEK(wrappedDek: String, kekVersion: Int): String {
        val kek = keks[kekVersion]
            ?: throw IllegalArgumentException("KEK version $kekVersion not found in available keys.")

        val combined = Base64.getDecoder().decode(wrappedDek)
        // Ensure combined has enough bytes for IV and at least some data
         if (combined.size < IV_SIZE) {
             throw IllegalArgumentException("Wrapped DEK is too short to contain IV.")
         }
        val iv = combined.copyOfRange(0, IV_SIZE)
        val encryptedDekBytes = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, kek, IvParameterSpec(iv)) // CHANGED: Use specific kek version
        val dekBytes = cipher.doFinal(encryptedDekBytes)
        return Base64.getEncoder().encodeToString(dekBytes)
    }

    /**
     * Gets the current version of the KEK.
     * This can be used to track which version of the KEK was used for encryption.
     *
     * @return The current KEK version.
     */
    override fun getKeyVersion(): Int = config.keyVersion

    /**
     * Converts a Base64-encoded key string to a SecretKey.
     *
     * @param encodedKey The Base64-encoded key string.
     * @return The SecretKey.
     */
    private fun secretKeyFromBase64(encodedKey: String): SecretKey {
        val decoded = Base64.getDecoder().decode(encodedKey)
        return SecretKeySpec(decoded, algorithm)
    }
}