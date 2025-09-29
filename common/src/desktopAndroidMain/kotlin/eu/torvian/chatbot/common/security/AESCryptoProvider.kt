package eu.torvian.chatbot.common.security

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

        fun generateRandomKey(
            algorithm: String = DEFAULT_ALGORITHM,
            keySizeBits: Int = DEFAULT_KEY_SIZE_BITS
        ): Either<CryptoError, String> = either {
            catch({
                val keyGenerator = KeyGenerator.getInstance(algorithm)
                keyGenerator.init(keySizeBits, SecureRandom())
                val key = keyGenerator.generateKey()
                Base64.getEncoder().encodeToString(key.encoded)
            }) { e: Exception ->
                raise(CryptoError.KeyGenerationError("Failed to generate random key: ${e.message}", e))
            }
        }
    }

    // Properties derived from config or defaults
    private val algorithm: String = config.algorithm ?: DEFAULT_ALGORITHM
    private val transformation: String = config.transformation ?: DEFAULT_TRANSFORMATION
    private val keySizeBits: Int = config.keySizeBits ?: DEFAULT_KEY_SIZE_BITS

    // Store a map of all KEKs, indexed by version
    private val keks: Either<CryptoError, Map<Int, SecretKey>> by lazy {
        either {
            catch({
                config.masterKeys.mapValues { (_, keyString) ->
                    val keyBytes = catch({
                        Base64.getDecoder().decode(keyString)
                    }) { e: IllegalArgumentException ->
                        raise(CryptoError.InvalidKey("Invalid Base64 encoding for master key: ${e.message}", e))
                    }

                    ensure(keyBytes.size * 8 == keySizeBits) {
                        CryptoError.ConfigurationError(
                            "Master key size (${keyBytes.size * 8} bits) does not match configured key size ($keySizeBits bits)"
                        )
                    }
                    SecretKeySpec(keyBytes, algorithm)
                }
            }) { e: Exception ->
                raise(CryptoError.ConfigurationError("Failed to initialize KEKs: ${e.message}", e))
            }
        }
    }

    // A specific reference to the current KEK for new encryptions
    private val currentKek: Either<CryptoError, SecretKey> by lazy {
        either {
            val keyMap = keks.bind()
            keyMap[config.keyVersion] ?: raise(CryptoError.KeyVersionNotFound(config.keyVersion))
        }
    }

    /**
     * Generates a new random Data Encryption Key (DEK).
     *
     * @return Either a CryptoError or a Base64-encoded string representation of the DEK.
     */
    override suspend fun generateDEK(): Either<CryptoError, String> = withContext(Dispatchers.IO) {
        generateRandomKey(algorithm, keySizeBits)
    }

    /**
     * Encrypts data using the provided DEK.
     *
     * @param plainText The plaintext data to encrypt.
     * @param dek The Base64-encoded DEK to use for encryption.
     * @return Either a CryptoError or a Base64-encoded string containing the encrypted data.
     */
    override suspend fun encryptData(plainText: String, dek: String): Either<CryptoError, String> = withContext(Dispatchers.IO) {
        either {
            catch({
                val dekKey = secretKeyFromBase64(dek).bind()
                val cipher = Cipher.getInstance(transformation)
                val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
                cipher.init(Cipher.ENCRYPT_MODE, dekKey, IvParameterSpec(iv))
                val encryptedBytes = cipher.doFinal(plainText.toByteArray())
                val combined = iv + encryptedBytes
                Base64.getEncoder().encodeToString(combined)
            }) { e: Exception ->
                raise(CryptoError.EncryptionError("Failed to encrypt data: ${e.message}", e))
            }
        }
    }

    /**
     * Decrypts data using the provided DEK.
     *
     * @param cipherText The Base64-encoded encrypted data.
     * @param dek The Base64-encoded DEK to use for decryption.
     * @return Either a CryptoError or the decrypted plaintext data.
     */
    override suspend fun decryptData(cipherText: String, dek: String): Either<CryptoError, String> = withContext(Dispatchers.IO) {
        either {
            catch({
                val dekKey = secretKeyFromBase64(dek).bind()
                val combined = catch({
                    Base64.getDecoder().decode(cipherText)
                }) { e: IllegalArgumentException ->
                    raise(CryptoError.InvalidCiphertext("Invalid Base64 encoding in ciphertext: ${e.message}", e))
                }

                // Ensure combined has enough bytes for IV and at least some data
                ensure(combined.size >= IV_SIZE) {
                    CryptoError.InvalidCiphertext("Ciphertext is too short to contain IV (minimum $IV_SIZE bytes required, got ${combined.size})")
                }

                val iv = combined.copyOfRange(0, IV_SIZE)
                val encryptedBytes = combined.copyOfRange(IV_SIZE, combined.size)
                val cipher = Cipher.getInstance(transformation)
                cipher.init(Cipher.DECRYPT_MODE, dekKey, IvParameterSpec(iv))
                val decryptedBytes = cipher.doFinal(encryptedBytes)
                String(decryptedBytes)
            }) { e: Exception ->
                raise(CryptoError.DecryptionError("Failed to decrypt data: ${e.message}", e))
            }
        }
    }

    /**
     * Encrypts (wraps) a DEK using the current KEK.
     *
     * @param dek The Base64-encoded DEK to encrypt.
     * @return Either a CryptoError or a Base64-encoded string containing the encrypted DEK.
     */
    override suspend fun wrapDEK(dek: String): Either<CryptoError, String> = withContext(Dispatchers.IO) {
        either {
            catch({
                val dekKey = secretKeyFromBase64(dek).bind()
                val kek = currentKek.bind()
                val cipher = Cipher.getInstance(transformation)
                val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
                cipher.init(Cipher.ENCRYPT_MODE, kek, IvParameterSpec(iv))
                val encryptedDekBytes = cipher.doFinal(dekKey.encoded)
                val combined = iv + encryptedDekBytes
                Base64.getEncoder().encodeToString(combined)
            }) { e: Exception ->
                raise(CryptoError.EncryptionError("Failed to wrap DEK: ${e.message}", e))
            }
        }
    }

    /**
     * Decrypts (unwraps) a DEK using the KEK of a specific version.
     *
     * @param wrappedDek The Base64-encoded encrypted DEK.
     * @param kekVersion The version of the KEK that was used to wrap this DEK.
     * @return Either a CryptoError or the decrypted DEK as a Base64-encoded string.
     */
    override suspend fun unwrapDEK(wrappedDek: String, kekVersion: Int): Either<CryptoError, String> = withContext(Dispatchers.IO) {
        either {
            catch({
                val keyMap = keks.bind()
                val kek = keyMap[kekVersion] ?: raise(CryptoError.KeyVersionNotFound(kekVersion))

                val combined = catch({
                    Base64.getDecoder().decode(wrappedDek)
                }) { e: IllegalArgumentException ->
                    raise(CryptoError.InvalidCiphertext("Invalid Base64 encoding in wrapped DEK: ${e.message}", e))
                }

                // Ensure combined has enough bytes for IV and at least some data
                ensure(combined.size >= IV_SIZE) {
                    CryptoError.InvalidCiphertext("Wrapped DEK is too short to contain IV (minimum $IV_SIZE bytes required, got ${combined.size})")
                }

                val iv = combined.copyOfRange(0, IV_SIZE)
                val encryptedDekBytes = combined.copyOfRange(IV_SIZE, combined.size)
                val cipher = Cipher.getInstance(transformation)
                cipher.init(Cipher.DECRYPT_MODE, kek, IvParameterSpec(iv))
                val dekBytes = cipher.doFinal(encryptedDekBytes)
                Base64.getEncoder().encodeToString(dekBytes)
            }) { e: Exception ->
                raise(CryptoError.DecryptionError("Failed to unwrap DEK: ${e.message}", e))
            }
        }
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
     * @return Either a CryptoError or the SecretKey.
     */
    private fun secretKeyFromBase64(encodedKey: String): Either<CryptoError, SecretKey> = either {
        catch({
            val decoded = catch({
                Base64.getDecoder().decode(encodedKey)
            }) { e: IllegalArgumentException ->
                raise(CryptoError.InvalidKey("Invalid Base64 encoding for key: ${e.message}", e))
            }
            SecretKeySpec(decoded, algorithm)
        }) { e: Exception ->
            raise(CryptoError.InvalidKey("Failed to create secret key: ${e.message}", e))
        }
    }
}