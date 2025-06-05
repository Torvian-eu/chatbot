**PR 3: Server Infrastructure - Secure Credential Manager (E5.S1)**
*   **Assignee:** Alex
*   **Reviewer:** Eric
*   **Description:** Implement secure credential storage for API keys using **AES envelope encryption** with a database-backed approach. This involves generating a unique Data Encryption Key (DEK) for each secret, encrypting the secret with the DEK, and then encrypting (wrapping) the DEK with a Key Encryption Key (KEK). The encrypted secret, the wrapped DEK, and the KEK version are stored together in the `api_secrets` database table, represented by the new `ApiSecretEntity` data class. The `DbEncryptedCredentialManager` implements the `CredentialManager` interface, leveraging the `EncryptionService` and `ApiSecretDao` to manage the lifecycle (store, retrieve, delete) of these secrets. Instead of direct OS integration, main application entities (like `LLMModel`) will now store the generated UUID alias which acts as a reference to the secret in the `api_secrets` table.
*   **Stories Addressed:** E5.S1 (full implementation - utilizing a database-backed encrypted approach), E7.S6 (coroutines in external/data services - DAO and Manager are suspend), E5.S2 (Securely Retrieve), E5.S3 (Securely Delete).
*   **Key Files:**
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/security/CredentialManager.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/EncryptionConfig.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/EncryptedSecret.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/security/CryptoProvider.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/security/AESCryptoProvider.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/security/EncryptionService.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ApiSecretsTable.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ApiSecretEntity.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/ApiSecretDao.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/data/exposed/ApiSecretDaoExposed.kt`
  *   `server/src/main/kotlin/eu/torvian/chatbot/server/service/security/DbEncryptedCredentialManager.kt`
  *   `server/src/test/kotlin/eu/torvian/chatbot/server/data/exposed/ApiSecretDaoExposedTest.kt`
  *   `server/src/test/kotlin/eu/torvian/chatbot/server/service/security/AESCryptoProviderTest.kt`
  *   `server/src/test/kotlin/eu/torvian/chatbot/server/service/security/DbEncryptedCredentialManagerTest.kt`
  *   `server/src/test/kotlin/eu/torvian/chatbot/server/service/security/EncryptionServiceTest.kt`

---

```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/domain/security/EncryptionConfig.kt
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
```
```kotlin
// file: server/src/main/kotlin/eu/torvian.chatbot.server.domain.security/EncryptedSecret.kt
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
```
```kotlin
// file: server/src/main/kotlin/eu/torvian.chatbot.server.service.security/CredentialManager.kt
package eu.torvian.chatbot.server.service.security
/**
* Interface for managing sensitive credentials (like API keys) using a secure storage mechanism.
*
* Implements [E5.S1 - Implement Secure API Key Storage].
*
* Implementations could use OS keystores, database-backed encryption, or external secret managers.
*
* @property storeCredential Stores a credential securely using a unique alias.
* @property getCredential Retrieves a securely stored credential using its alias/reference ID.
* @property deleteCredential Deletes a securely stored credential using its alias/reference ID.
*/
interface CredentialManager {
    /**
     * Stores a credential securely using a unique alias.
     *
     * The implementation should generate a unique, opaque alias or accept one provided by the caller.
     * This alias (reference ID) is what is stored in the database (e.g., in `LLMModels`), not the raw credential.
     * The actual storage mechanism interacts with the underlying secure storage API (e.g., database, OS API).
     *
     * Implements part of E5.S1. Must be [suspend] as it performs blocking I/O with the storage.
     *
     * @param credential The sensitive string (e.g., API key) to store.
     * @return A string alias/reference ID that can be used to retrieve the credential,
     *         or null if storage failed. This ID is what is stored in entities referencing the secret.
     */
    suspend fun storeCredential(credential: String): String?
    
    /**
     * Retrieves a securely stored credential using its alias/reference ID.
     *
     * Implements part of [E5.S2 - Securely Retrieve API Key]. Must be [suspend] as it performs blocking I/O with the storage.
     *
     * @param alias The alias/reference ID returned by [storeCredential].
     * @return The decrypted credential string, or null if not found or retrieval failed.
     */
    suspend fun getCredential(alias: String): String?
    
    /**
     * Deletes a securely stored credential using its alias/reference ID.
     *
     * Implements part of [E5.S3 - Securely Delete API Key]. Must be [suspend] as it performs blocking I/O with the storage.
     *
     * @param alias The alias/reference ID of the credential to delete.
     * @return True if deletion was successful (or the credential wasn't found, which is a success state for deletion), false otherwise on failure.
     */
    suspend fun deleteCredential(alias: String): Boolean
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian.chatbot.server.service.security/CryptoProvider.kt
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
```
```kotlin
// file: server/src/main/kotlin/eu/torvian.chatbot.server.service.security/AESCryptoProvider.kt
package eu.torvian.chatbot.server.service.security
import eu.torvian.chatbot.server.domain.security.EncryptionConfig
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
/**
 *   Implementation of [CryptoProvider] using AES encryption.
 *
 *   This class provides a concrete implementation of envelope encryption using AES:
 *   Data is encrypted with a Data Encryption Key (DEK)
 *   The DEK is encrypted with a Key Encryption Key (KEK)
 *
 *   All methods work with Base64-encoded strings to hide implementation details
 *   and decouple the rest of the system from crypto-specific types.
 *
 *   @property config The encryption configuration to use
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

    // The KEK is stored internally as a SecretKey, constructed from the Base64-encoded masterKey
    private val kek: SecretKey by lazy {
        val keyBytes = Base64.getDecoder().decode(config.masterKey)
        // Validate key size if necessary, e.g., if keyBytes.size * 8 != keySizeBits
        if (keyBytes.size * 8 != keySizeBits) {
             // Log a warning or throw an exception if the provided key doesn't match the configured size
             // Using a fixed size spec might be safer if keySizeBits is derived from config
        }
        SecretKeySpec(keyBytes, algorithm)
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
     * Encrypts (wraps) a DEK using the KEK.
     *
     * @param dek The Base64-encoded DEK to encrypt.
     * @return A Base64-encoded string containing the encrypted DEK.
     */
    override fun wrapDEK(dek: String): String {
        val dekKey = secretKeyFromBase64(dek)
        val cipher = Cipher.getInstance(transformation)
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, kek, IvParameterSpec(iv))
        val encryptedDekBytes = cipher.doFinal(dekKey.encoded)
        val combined = iv + encryptedDekBytes
        return Base64.getEncoder().encodeToString(combined)
    }
    /**
     * Decrypts (unwraps) a DEK using the KEK.
     *
     * @param wrappedDek The Base64-encoded encrypted DEK.
     * @return The decrypted DEK as a Base64-encoded string.
     */
    override fun unwrapDEK(wrappedDek: String): String {
        val combined = Base64.getDecoder().decode(wrappedDek)
        // Ensure combined has enough bytes for IV and at least some data
         if (combined.size < IV_SIZE) {
             throw IllegalArgumentException("Wrapped DEK is too short to contain IV.")
         }
        val iv = combined.copyOfRange(0, IV_SIZE)
        val encryptedDekBytes = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, kek, IvParameterSpec(iv))
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
```
```kotlin
// file: server/src/main/kotlin/eu/torvian.chatbot.server.service.security/EncryptionService.kt
package eu.torvian.chatbot.server.service.security
import eu.torvian.chatbot.server.domain.security.EncryptedSecret
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
     * @return An [EncryptedSecret] containing the encrypted secret, encrypted DEK, and key version.
     */
    fun encrypt(plainText: String): EncryptedSecret {
        val dek = cryptoProvider.generateDEK()
        val encryptedSecret = cryptoProvider.encryptData(plainText, dek)
        val wrappedDek = cryptoProvider.wrapDEK(dek)
        val keyVersion = cryptoProvider.getKeyVersion()
        return EncryptedSecret(
            encryptedSecret = encryptedSecret,
            encryptedDEK = wrappedDek,
            keyVersion = keyVersion
        )
    }
    /**
     * Decrypts a secret using envelope encryption.
     *
     * This method:
     * 1. Decrypts the DEK using the KEK
     * 2. Decrypts the secret using the DEK
     *
     * @param encrypted The [EncryptedSecret] containing the encrypted secret and DEK details.
     * @return The decrypted plaintext secret.
     */
    fun decrypt(encrypted: EncryptedSecret): String {
        // You might add a check here for encrypted.keyVersion if you support multiple KEKs for decryption
        val dek = cryptoProvider.unwrapDEK(encrypted.encryptedDEK)
        return cryptoProvider.decryptData(encrypted.encryptedSecret, dek)
    }
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian.chatbot.server.data.models/ApiSecretsTable.kt
package eu.torvian.chatbot.server.data.models
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
/**
 * Represents the database table schema for storing encrypted API secrets and other sensitive data.
 *
 * This table holds the encrypted credential, the wrapped Data Encryption Key (DEK),
 * and the Key Encryption Key (KEK) version used for envelope encryption.
 *
 * @property alias The unique identifier (UUID) for the secret, used as the primary key and reference from other tables (like LLMModels).
 * @property encrypted_credential The API key (or other sensitive data) encrypted with a DEK, Base64 encoded.
 * @property wrapped_dek The Data Encryption Key (DEK) encrypted with the Key Encryption Key (KEK), Base64 encoded.
 * @property key_version The version of the Key Encryption Key (KEK) used.
 * @property created_at Timestamp when the secret was created.
 * @property updated_at Timestamp when the secret was last updated.
 */
object ApiSecretsTable : Table("api_secrets") {
    // The alias (UUID) is the primary key, used to reference this secret from other tables.
    // VARCHAR(36) is standard for UUID string representation.
    val alias: Column<String> = varchar("alias", 36)
    // Using text for encrypted_credential allows for potentially larger encrypted data
    val encrypted_credential: Column<String> = text("encrypted_credential")
    // varchar(255) should be sufficient for a Base64 encoded 256-bit AES wrapped DEK + IV (~65 chars)
    val wrapped_dek: Column<String> = varchar("wrapped_dek", 255)
    val key_version: Column<Int> = integer("key_version") // Link to EncryptionConfig.keyVersion
    val created_at = long("created_at")
    val updated_at = long("updated_at") // Could be updated on write/overwrite
    override val primaryKey = PrimaryKey(alias)
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian/chatbot/server/data/models/ApiSecretEntity.kt
package eu.torvian.chatbot.server.data.models
import org.jetbrains.exposed.sql.ResultRow
/**
 * Represents a row from the 'api_secrets' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 *
 * @property alias Unique identifier for the secret (UUID string).
 * @property encryptedCredential The encrypted sensitive data (Base64).
 * @property wrappedDek The wrapped Data Encryption Key (DEK) (Base64).
 * @property keyVersion The version of the Key Encryption Key (KEK) used.
 * @property createdAt Timestamp when the secret was created (epoch milliseconds).
 * @property updatedAt Timestamp when the secret was last updated (epoch milliseconds).
 */
data class ApiSecretEntity(
    val alias: String,
    val encryptedCredential: String,
    val wrappedDek: String,
    val keyVersion: Int,
    val createdAt: Long,
    val updatedAt: Long
)
/**
 * Extension function to map an Exposed [ResultRow] to an [ApiSecretEntity].
 */
fun ResultRow.toApiSecretEntity(): ApiSecretEntity {
    return ApiSecretEntity(
        alias = this[ApiSecretsTable.alias],
        encryptedCredential = this[ApiSecretsTable.encrypted_credential],
        wrappedDek = this[ApiSecretsTable.wrapped_dek],
        keyVersion = this[ApiSecretsTable.key_version],
        createdAt = this[ApiSecretsTable.created_at],
        updatedAt = this[ApiSecretsTable.updated_at]
    )
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian.chatbot.server.data.dao/ApiSecretDao.kt
package eu.torvian.chatbot.server.data.dao
import eu.torvian.chatbot.server.data.models.ApiSecretEntity
import eu.torvian.chatbot.server.domain.security.EncryptedSecret
import kotlinx.datetime.Instant
/**
 * Repository interface for managing encrypted API secrets in the database.
 *
 * Defines operations for saving, retrieving, and deleting encrypted secrets
 * stored in the dedicated `api_secrets` table.
 */
interface ApiSecretDao {
    /**
     * Saves an encrypted secret to the database.
     * If a secret with the given alias already exists, it should be overwritten (upsert logic).
     *
     * @param apiSecretEntity The encrypted secret data to save.
     */
    suspend fun saveSecret(apiSecretEntity: ApiSecretEntity)
    /**
     * Finds an encrypted secret by its alias.
     *
     * @param alias The unique identifier (UUID) of the secret to find.
     * @return The encrypted secret data if found, null otherwise.
     */
    suspend fun findSecret(alias: String): ApiSecretEntity?
    /**
     * Deletes an encrypted secret by its alias.
     *
     * @param alias The unique identifier (UUID) of the secret to delete.
     * @return True if the secret was deleted successfully (or didn't exist), false otherwise.
     */
    suspend fun deleteSecret(alias: String): Boolean
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian.chatbot.server.data.exposed/ApiSecretDaoExposed.kt
package eu.torvian.chatbot.server.data.exposed
import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.models.ApiSecretEntity
import eu.torvian.chatbot.server.data.models.ApiSecretsTable
import eu.torvian.chatbot.server.data.models.toApiSecretEntity
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
/**
 * Exposed ORM implementation of [ApiSecretDao].
 *
 * Manages encrypted API secrets in the `api_secrets` database table using Exposed.
 *
 * @property transactionScope The transaction scope for database access within coroutines.
 */
class ApiSecretDaoExposed(
    private val transactionScope: TransactionScope
) : ApiSecretDao {
    companion object {
        private val logger: Logger = LogManager.getLogger(ApiSecretDaoExposed::class.java)
    }
    override suspend fun saveSecret(apiSecretEntity: ApiSecretEntity) =
        transactionScope.transaction {
            // Check if exists
            val existing = ApiSecretsTable.selectAll().where {
                ApiSecretsTable.alias eq apiSecretEntity.alias
            }.singleOrNull()
            if (existing == null) {
                // Insert new secret
                ApiSecretsTable.insert {
                    it[alias] = apiSecretEntity.alias
                    it[encrypted_credential] = apiSecretEntity.encryptedCredential
                    it[wrapped_dek] = apiSecretEntity.wrappedDek
                    it[key_version] = apiSecretEntity.keyVersion
                    it[created_at] = apiSecretEntity.createdAt
                    it[updated_at] = apiSecretEntity.updatedAt
                }
                logger.debug("DAO: Inserted new secret with alias: ${apiSecretEntity.alias}")
            } else {
                // Update existing secret
                ApiSecretsTable.update({
                    ApiSecretsTable.alias eq apiSecretEntity.alias
                }) {
                    it[encrypted_credential] = apiSecretEntity.encryptedCredential
                    it[wrapped_dek] = apiSecretEntity.wrappedDek
                    it[key_version] = apiSecretEntity.keyVersion
                    it[updated_at] = apiSecretEntity.updatedAt
                }
                logger.debug("DAO: Updated existing secret with alias: ${apiSecretEntity.alias}")
            }
        }
    override suspend fun findSecret(alias: String): ApiSecretEntity? =
        transactionScope.transaction {
            ApiSecretsTable
                .selectAll().where { ApiSecretsTable.alias eq alias }
                .singleOrNull()?.toApiSecretEntity()
                .also {
                    if (it == null) {
                        logger.debug("DAO: Secret with alias $alias not found in database.")
                    } else {
                        logger.debug("DAO: Found secret with alias $alias in database.")
                    }
                }
        }
    override suspend fun deleteSecret(alias: String): Boolean =
        transactionScope.transaction {
            try {
                val deletedRows = ApiSecretsTable.deleteWhere { ApiSecretsTable.alias eq alias }
                logger.debug("DAO: Attempted to delete secret with alias $alias. Rows deleted: $deletedRows")
                // Return true if the delete operation itself succeeded (didn't throw), regardless of row count.
                // The service/caller can interpret 0 rows deleted as "not found", which is success for delete semantics.
                true
            } catch (e: Exception) {
                logger.error("DAO: Exception during database delete for alias $alias", e)
                false // Indicate failure if a database exception occurs
            }
        }
}
```
```kotlin
// file: server/src/main/kotlin/eu/torvian.chatbot.server.service.security/DbEncryptedCredentialManager.kt
package eu.torvian.chatbot.server.service.security
import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.models.ApiSecretEntity
import eu.torvian.chatbot.server.domain.security.EncryptedSecret
import java.util.UUID
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
/**
 * Implementation of [CredentialManager] using envelope encryption and storing
 * encrypted secrets in a dedicated database table via [ApiSecretDao].
 *
 * This class leverages an [EncryptionService] (which holds the KEK) and an
 * [ApiSecretDao] (which interacts with the database table).
 *
 * The main application tables will store the `alias` (UUID) returned by [storeCredential].
 *
 * Implements [E5.S1, E5.S2, E5.S3] using this database-backed encryption approach.
 * All methods are [suspend] as they interact with the database (via DAO).
 */
class DbEncryptedCredentialManager(
    private val encryptionService: EncryptionService,
    private val apiSecretDao: ApiSecretDao
) : CredentialManager {
    companion object {
        private val logger: Logger = LogManager.getLogger(DbEncryptedCredentialManager::class.java)
    }
    /**
     * Stores a credential securely in the database via envelope encryption.
     *
     * Generates a unique UUID as the alias/reference ID.
     * Uses the [EncryptionService] to encrypt the credential and wrap the DEK.
     * Stores the resulting encrypted data in the dedicated secrets table via [ApiSecretDao].
     *
     * @param credential The sensitive string (e.g., API key) to store.
     * @return A string alias/reference ID (the generated UUID) that can be used to retrieve the credential,
     *         or null if storage failed.
     */
    override suspend fun storeCredential(credential: String): String? {
        val alias = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        try {
            // Use the encryption service to perform envelope encryption
            val encryptedData = encryptionService.encrypt(credential)
            // Save the encrypted data (encrypted credential, wrapped DEK, key version)
            // to the dedicated secrets database table using the DAO.
            apiSecretDao.saveSecret(
                ApiSecretEntity(
                    alias = alias,
                    encryptedCredential = encryptedData.encryptedSecret,
                    wrappedDek = encryptedData.encryptedDEK,
                    keyVersion = encryptedData.keyVersion,
                    createdAt = now,
                    updatedAt = now
                )
            )
            logger.info("CredentialManager: Successfully stored credential in database with alias: $alias")
            return alias // Return the generated alias/ID upon success
        } catch (e: Exception) {
            logger.error("CredentialManager: Failed to store credential in database for alias $alias", e)
            return null // Indicate failure
        }
    }
    /**
     * Retrieves a securely stored credential from the database using its alias.
     *
     * Reads the encrypted data from the dedicated secrets table using the alias via [ApiSecretDao].
     * Uses the [EncryptionService] to unwrap the DEK and decrypt the credential.
     *
     * @param alias The alias/reference ID (the UUID string) of the credential to retrieve.
     * @return The decrypted credential string, or null if not found or retrieval failed.
     */
    override suspend fun getCredential(alias: String): String? {
        try {
            // Find the encrypted data in the secrets table using the DAO
            val encryptedData = apiSecretDao.findSecret(alias)
            if (encryptedData != null) {
                // Use the encryption service to decrypt the credential
                val credentialString = encryptionService.decrypt(
                    EncryptedSecret(
                        encryptedSecret = encryptedData.encryptedCredential,
                        encryptedDEK = encryptedData.wrappedDek,
                        keyVersion = encryptedData.keyVersion
                    )
                )
                logger.debug("CredentialManager: Successfully retrieved credential from database for alias: $alias")
                return credentialString // Return the retrieved credential string
            } else {
                // Secret not found in the database
                logger.debug("CredentialManager: Credential alias not found in database: $alias")
                return null // Indicate not found
            }
        } catch (e: Exception) {
            // This could be a decryption error, DAO error, etc.
            logger.error("CredentialManager: Exception during credential retrieval from database for alias $alias", e)
            return null // Indicate failure
        }
    }
    /**
     * Deletes a securely stored credential from the database using its alias.
     *
     * Deletes the entry from the dedicated secrets table using the alias via [ApiSecretDao].
     *
     * @param alias The alias/reference ID (the UUID string) of the credential to delete.
     * @return True if deletion was successful (or the credential wasn't found), false otherwise on failure.
     */
    override suspend fun deleteCredential(alias: String): Boolean {
         try {
             // Delete the secret from the secrets table using the DAO
             val success = apiSecretDao.deleteSecret(alias)
             if (success) {
                 logger.info("CredentialManager: Successfully deleted credential from database with alias: $alias")
                 return true // Indicate success (DAO returns true on successful DB op, even if 0 rows deleted)
             } else {
                 // apiSecretDao.deleteSecret logs SEVERE errors itself, so just return false here
                 // indicating the DB operation itself failed.
                 return false // Indicate failure
             }
         } catch (e: Exception) {
             // Catch any unexpected exceptions from the DAO call
             logger.error("CredentialManager: Exception during credential delete from database for alias $alias", e)
             return false // Indicate failure due to exception
         }
    }
}
```
```kotlin
// file: server/src/test/kotlin/eu/torvian/chatbot/server/data/exposed/ApiSecretDaoExposedTest.kt
package eu.torvian.chatbot.server.data.exposed
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.models.ApiSecretEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
/**
 * Tests for [ApiSecretDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ApiSecretDao]:
 * - Saving secrets (insert and update)
 * - Finding secrets by alias
 * - Deleting secrets by alias
 * - Handling cases where secrets don't exist.
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class ApiSecretDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var apiSecretDao: ApiSecretDao
    private lateinit var testDataManager: TestDataManager
    // Test data
    private val apiSecret1 = ApiSecretEntity(
        alias = "alias1",
        encryptedCredential = "encrypted_secret1",
        wrappedDek = "encrypted_dek1",
        keyVersion = 1,
        createdAt = TestDefaults.DEFAULT_INSTANT_MILLIS,
        updatedAt = TestDefaults.DEFAULT_INSTANT_MILLIS
    )
    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        apiSecretDao = container.get()
        testDataManager = container.get()
        testDataManager.createTables(setOf(Table.API_SECRETS))
    }
    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }
    @Test
    fun `saveSecret should insert a new secret if alias does not exist`() = runTest {
        val secretToSave = apiSecret1
        apiSecretDao.saveSecret(secretToSave)
        val foundSecret = apiSecretDao.findSecret(apiSecret1.alias)
        assertNotNull(foundSecret, "Secret should be found after insertion")
        assertEquals(secretToSave, foundSecret)
    }
    @Test
    fun `saveSecret should update an existing secret if alias already exists`() = runTest {
        // Arrange: Insert an initial secret
        val initialEntry = apiSecret1
        testDataManager.insertApiSecret(initialEntry)
        // Act: Save a different secret with the *same* alias
        val updatedSecretData = initialEntry.copy(
            encryptedCredential = "updated_secret",
            wrappedDek = "updated_dek",
            keyVersion = 2,
            updatedAt = initialEntry.updatedAt + 60000L
        )
        apiSecretDao.saveSecret(updatedSecretData)
        // Assert: Find the secret and verify it's the updated version
        val foundSecret = apiSecretDao.findSecret(initialEntry.alias)
        assertNotNull(foundSecret, "Secret should still be found after update")
        assertEquals(updatedSecretData, foundSecret, "Found secret data should be the updated data")
    }
     @Test
     fun `findSecret should return the correct secret for a given alias`() = runTest {
         // Arrange: Insert multiple secrets
         val entry1 = apiSecret1
         val entry2 = apiSecret1.copy(alias = "alias2")
         testDataManager.setup(TestDataSet(apiSecrets = listOf(entry1, entry2)))
         // Act & Assert: Find each secret
         val found1 = apiSecretDao.findSecret(entry1.alias)
         assertNotNull(found1)
         assertEquals(entry1, found1)
         val found2 = apiSecretDao.findSecret(entry2.alias)
         assertNotNull(found2)
         assertEquals(entry2, found2)
     }
     @Test
     fun `findSecret should return null if alias does not exist`() = runTest {
         // Arrange:
         val entry1 = apiSecret1
         testDataManager.insertApiSecret(entry1)
         // Act
         val foundSecret = apiSecretDao.findSecret("non-existent-alias")
         // Assert
         assertNull(foundSecret, "Finding a non-existent alias should return null")
     }
     @Test
     fun `deleteSecret should remove the secret with the given alias`() = runTest {
         // Arrange: Insert a secret
         val entryToDelete = apiSecret1
         testDataManager.insertApiSecret(entryToDelete)
         // Verify it exists initially
         assertNotNull(apiSecretDao.findSecret(entryToDelete.alias), "Secret should exist before deletion")
         // Act
         val isDeleted = apiSecretDao.deleteSecret(entryToDelete.alias)
         // Assert
         assertTrue(isDeleted, "deleteSecret should return true on success")
         assertNull(apiSecretDao.findSecret(entryToDelete.alias), "Secret should not be found after deletion")
     }
    @Test
    fun `deleteSecret should return true if alias does not exist`() = runTest {
        // Arrange:
        val entry1 = apiSecret1
        testDataManager.insertApiSecret(entry1)
        // Act
        val isDeleted = apiSecretDao.deleteSecret("non-existent-alias")
        // Assert
        // The DAO implementation returns true even if no rows were deleted, as the state matches the requested outcome (secret is gone).
        assertTrue(isDeleted, "deleteSecret should return true even if the secret did not exist")
    }
    // Add more test cases here for edge cases, concurrency (if applicable), etc.
}
```
```kotlin
// file: server/src/test/kotlin/eu/torvian/chatbot/server/service/security/AESCryptoProviderTest.kt
package eu.torvian.chatbot.server.service.security;
import eu.torvian.chatbot.server.domain.security.EncryptionConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
class AESCryptoProviderTest {
    private lateinit var cryptoProvider: AESCryptoProvider
    private lateinit var config: EncryptionConfig
    @BeforeEach
    fun setup() {
        // Create a random test master key (Base64-encoded 256-bit key)
        val random = java.security.SecureRandom()
        val keyBytes = ByteArray(32)
        random.nextBytes(keyBytes)
        val masterKey = Base64.getEncoder().encodeToString(keyBytes)
        config = EncryptionConfig(masterKey = masterKey, keyVersion = 1)
        cryptoProvider = AESCryptoProvider(config)
    }
    @Test
    fun `generateDEK should return a non-empty Base64 string`() {
        // Act
        val dek = cryptoProvider.generateDEK()
        // Assert
        assertTrue(dek.isNotEmpty())
        // Verify it's a valid Base64 string
        Base64.getDecoder().decode(dek)
    }
    @Test
    fun `encryptData and decryptData should form a complete cycle`() {
        // Arrange
        val plainText = "This is a secret message"
        val dek = cryptoProvider.generateDEK()
        // Act
        val cipherText = cryptoProvider.encryptData(plainText, dek)
        val decryptedText = cryptoProvider.decryptData(cipherText, dek)
        // Assert
        assertNotEquals(plainText, cipherText, "Encrypted text should be different from plain text")
        assertEquals(plainText, decryptedText, "Decrypted text should match original plain text")
    }
    @Test
    fun `wrapDEK and unwrapDEK should form a complete cycle`() {
        // Arrange
        val dek = cryptoProvider.generateDEK()
        // Act
        val wrappedDek = cryptoProvider.wrapDEK(dek)
        val unwrappedDek = cryptoProvider.unwrapDEK(wrappedDek)
        // Assert
        assertNotEquals(dek, wrappedDek, "Wrapped DEK should be different from original DEK")
        assertEquals(dek, unwrappedDek, "Unwrapped DEK should match original DEK")
    }
    @Test
    fun `getKeyVersion should return the configured key version`() {
        // Act
        val keyVersion = cryptoProvider.getKeyVersion()
        // Assert
        assertEquals(config.keyVersion, keyVersion)
    }
    @Test
    fun `encryptData should produce different ciphertexts for the same plaintext`() {
        // Arrange
        val plainText = "This is a secret message"
        val dek = cryptoProvider.generateDEK()
        // Act
        val cipherText1 = cryptoProvider.encryptData(plainText, dek)
        val cipherText2 = cryptoProvider.encryptData(plainText, dek)
        // Assert
        assertNotEquals(cipherText1, cipherText2, 
            "Encrypting the same plaintext twice should produce different ciphertexts due to random IV")
    }
    @Test
    fun `wrapDEK should produce different wrapped keys for the same DEK`() {
        // Arrange
        val dek = cryptoProvider.generateDEK()
        // Act
        val wrappedDek1 = cryptoProvider.wrapDEK(dek)
        val wrappedDek2 = cryptoProvider.wrapDEK(dek)
        // Assert
        assertNotEquals(wrappedDek1, wrappedDek2, 
            "Wrapping the same DEK twice should produce different results due to random IV")
    }
    @Test
    fun `encryptData should handle empty string`() {
        // Arrange
        val plainText = ""
        val dek = cryptoProvider.generateDEK()
        // Act
        val cipherText = cryptoProvider.encryptData(plainText, dek)
        val decryptedText = cryptoProvider.decryptData(cipherText, dek)
        // Assert
        assertEquals(plainText, decryptedText, "Should correctly encrypt and decrypt empty string")
    }
    @Test
    fun `encryptData should handle large string`() {
        // Arrange
        val plainText = "A".repeat(10000) // 10KB string
        val dek = cryptoProvider.generateDEK()
        // Act
        val cipherText = cryptoProvider.encryptData(plainText, dek)
        val decryptedText = cryptoProvider.decryptData(cipherText, dek)
        // Assert
        assertEquals(plainText, decryptedText, "Should correctly encrypt and decrypt large string")
    }
}
```
```kotlin
// file: server/src/test/kotlin/eu/torvian/chatbot/server/service/security/DbEncryptedCredentialManagerTest.kt
package eu.torvian.chatbot.server.service.security
import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.models.ApiSecretEntity
import eu.torvian.chatbot.server.domain.security.EncryptedSecret
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.*
import kotlin.test.*
/**
 * Unit tests for [DbEncryptedCredentialManager].
 *
 * This test suite verifies the core orchestration logic of the database-backed credential manager,
 * ensuring it correctly interacts with its dependencies: [EncryptionService] and [ApiSecretDao].
 *
 * Both dependencies are mocked using MockK, focusing on testing the logic *within* the manager
 * and how it responds to success/failure from its collaborators, rather than testing the
 * dependencies themselves.
 */
class DbEncryptedCredentialManagerTest {
    private lateinit var apiSecretDao: ApiSecretDao // The mocked DAO
    private lateinit var encryptionService: EncryptionService // The mocked service
    private lateinit var credentialManager: CredentialManager // The class under test
    // --- Test Data ---
    private val testPlainCredential = "my_super_secret_api_key"
    private val testAlias = "test-alias-123" // A specific alias for lookup/delete tests
    private val testKeyVersion = 1
    private val testEncryptedData = EncryptedSecret(
        encryptedSecret = "encrypted_credential_data",
        encryptedDEK = "wrapped_data_encryption_key",
        keyVersion = testKeyVersion
    )
    // Entity structure we expect the manager to save/retrieve
    // Note: createdAt/updatedAt will be dynamically generated by the manager,
    // so we won't assert exact timestamps, but will capture and check structure.
    private fun expectedApiSecretEntity(alias: String, encryptedSecret: EncryptedSecret): ApiSecretEntity {
        // Return a template entity structure without exact timestamps
        return ApiSecretEntity(
            alias = alias,
            encryptedCredential = encryptedSecret.encryptedSecret,
            wrappedDek = encryptedSecret.encryptedDEK,
            keyVersion = encryptedSecret.keyVersion,
            // Timestamps will be set by the manager under test
            createdAt = 0L, // Placeholder
            updatedAt = 0L  // Placeholder
        )
    }

    @BeforeEach
    fun setUp() {
        // Mock both dependencies
        apiSecretDao = mockk()
        encryptionService = mockk()
        // Create the class under test with mocked dependencies
        credentialManager = DbEncryptedCredentialManager(encryptionService, apiSecretDao)
        // Configure mock responses that are common across tests if needed
        // (None strictly needed for this specific setup, mocks default to null/0/false)
    }
    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(apiSecretDao, encryptionService)
    }
    // --- storeCredential Tests ---
    @Test
    fun `storeCredential should encrypt and save credential successfully`() = runTest {
        // Arrange
        // Configure the mock encryption service to return specific encrypted data
        every { encryptionService.encrypt(testPlainCredential) } returns testEncryptedData
        // Configure the mock apiSecretDao to succeed (return Unit for a Unit function or true for Boolean)
        // apiSecretDao.saveSecret returns Unit
        coEvery { apiSecretDao.saveSecret(any()) } returns Unit // Mock successful save
        // Use a slot to capture the ApiSecretEntity passed to apiSecretDao.saveSecret
        val apiSecretEntitySlot = slot<ApiSecretEntity>()
        // Act
        val resultAlias = credentialManager.storeCredential(testPlainCredential)
        // Assert
        assertNotNull(resultAlias, "storeCredential should return a non-null alias on success")
        // Verify the alias is a valid UUID string (optional but good sanity check)
        assertDoesNotThrow { UUID.fromString(resultAlias) }
        // Verify encryptionService was called correctly
        verify(exactly = 1) { encryptionService.encrypt(testPlainCredential) }
        // Verify apiSecretDao.saveSecret was called exactly once with the captured entity
        coVerify(exactly = 1) { apiSecretDao.saveSecret(capture(apiSecretEntitySlot)) }
        val savedEntity = apiSecretEntitySlot.captured
        // Assert the contents of the saved entity structure
        assertEquals(resultAlias, savedEntity.alias, "Saved entity alias should match the returned alias")
        assertEquals(
            testEncryptedData.encryptedSecret,
            savedEntity.encryptedCredential,
            "Saved entity encrypted credential mismatch"
        )
        assertEquals(testEncryptedData.encryptedDEK, savedEntity.wrappedDek, "Saved entity wrapped DEK mismatch")
        assertEquals(testEncryptedData.keyVersion, savedEntity.keyVersion, "Saved entity key version mismatch")
        // Check timestamps are present and equal (since it's an initial save)
        assertTrue(savedEntity.createdAt > 0, "Saved entity createdAt should be set")
        assertTrue(savedEntity.updatedAt > 0, "Saved entity updatedAt should be set")
        assertEquals(
            savedEntity.createdAt,
            savedEntity.updatedAt,
            "createdAt and updatedAt should be the same on initial save"
        )
    }
    @Test
    fun `storeCredential should return null if encryption fails`() = runTest {
        // Arrange
        // Configure the mock encryption service to throw an exception
        val encryptionException = RuntimeException("Encryption failed!")
        every { encryptionService.encrypt(testPlainCredential) } throws encryptionException
        // Act
        val resultAlias = credentialManager.storeCredential(testPlainCredential)
        // Assert
        assertNull(resultAlias, "storeCredential should return null if encryption fails")
        // Verify encryptionService was called
        verify(exactly = 1) { encryptionService.encrypt(testPlainCredential) }
        // Verify apiSecretDao.saveSecret was NOT called
        coVerify(exactly = 0) { apiSecretDao.saveSecret(any()) }
    }
    @Test
    fun `storeCredential should return null if saving to database fails`() = runTest {
        // Arrange
        // Configure encryption service to succeed
        every { encryptionService.encrypt(testPlainCredential) } returns testEncryptedData
        // Configure apiSecretDao to throw an exception when saveSecret is called
        val dbSaveException = RuntimeException("DB Save failed!")
        coEvery { apiSecretDao.saveSecret(any()) } throws dbSaveException
        // Act
        val resultAlias = credentialManager.storeCredential(testPlainCredential)
        // Assert
        assertNull(resultAlias, "storeCredential should return null if saving to DB fails")
        // Verify encryptionService was called
        verify(exactly = 1) { encryptionService.encrypt(testPlainCredential) }
        // Verify apiSecretDao.saveSecret was called and threw
        coVerify(exactly = 1) { apiSecretDao.saveSecret(any()) }
    }
    // --- getCredential Tests ---
    @Test
    fun `getCredential should retrieve and decrypt credential successfully`() = runTest {
        // Arrange
        // Create a sample entity that we will mock the DAO to return
        val retrievedEntity = expectedApiSecretEntity(testAlias, testEncryptedData).copy(
            createdAt = System.currentTimeMillis(), // Add realistic timestamps for retrieval mock
            updatedAt = System.currentTimeMillis()
        )
        // Configure the mock apiSecretDao to return the entity for the test alias
        coEvery { apiSecretDao.findSecret(testAlias) } returns retrievedEntity
        // Configure the mock encryption service to return the plaintext when decrypting the specific encrypted data
        every { encryptionService.decrypt(testEncryptedData) } returns testPlainCredential
        // Act
        val resultCredential = credentialManager.getCredential(testAlias)
        // Assert
        assertNotNull(resultCredential, "getCredential should return a non-null credential on success")
        assertEquals(testPlainCredential, resultCredential, "Retrieved credential should match the original plaintext")
        // Verify apiSecretDao.findSecret was called correctly
        coVerify(exactly = 1) { apiSecretDao.findSecret(testAlias) }
        // Verify encryptionService.decrypt was called correctly
        verify(exactly = 1) { encryptionService.decrypt(testEncryptedData) }
    }
    @Test
    fun `getCredential should return null if alias is not found in database`() = runTest {
        // Arrange
        val nonExistentAlias = "non-existent-alias"
        // Configure the mock apiSecretDao to return null for a non-existent alias
        coEvery { apiSecretDao.findSecret(nonExistentAlias) } returns null
        // Act
        val resultCredential = credentialManager.getCredential(nonExistentAlias)
        // Assert
        assertNull(resultCredential, "getCredential should return null if alias is not found")
        // Verify apiSecretDao.findSecret was called correctly
        coVerify(exactly = 1) { apiSecretDao.findSecret(nonExistentAlias) }
        // Verify encryptionService.decrypt was NOT called
        verify(exactly = 0) { encryptionService.decrypt(any()) }
    }
    @Test
    fun `getCredential should return null if retrieving from database fails`() = runTest {
        // Arrange
        // Configure apiSecretDao to throw an exception when findSecret is called
        val dbFindException = RuntimeException("DB Find failed!")
        coEvery { apiSecretDao.findSecret(testAlias) } throws dbFindException
        // Act
        val resultCredential = credentialManager.getCredential(testAlias)
        // Assert
        assertNull(resultCredential, "getCredential should return null if retrieving from DB fails")
        // Verify apiSecretDao.findSecret was called and threw
        coVerify(exactly = 1) { apiSecretDao.findSecret(testAlias) }
        // Verify encryptionService.decrypt was NOT called
        verify(exactly = 0) { encryptionService.decrypt(any()) }
    }
    @Test
    fun `getCredential should return null if decryption fails`() = runTest {
        // Arrange
        // Create a sample entity that we will mock the DAO to return
        val retrievedEntity = expectedApiSecretEntity(testAlias, testEncryptedData).copy(
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        // Configure the mock apiSecretDao to return the entity
        coEvery { apiSecretDao.findSecret(testAlias) } returns retrievedEntity
        // Configure the mock encryption service to throw an exception when decrypting
        val decryptionException = RuntimeException("Decryption failed!")
        every { encryptionService.decrypt(testEncryptedData) } throws decryptionException
        // Act
        val resultCredential = credentialManager.getCredential(testAlias)
        // Assert
        assertNull(resultCredential, "getCredential should return null if decryption fails")
        // Verify apiSecretDao.findSecret was called
        coVerify(exactly = 1) { apiSecretDao.findSecret(testAlias) }
        // Verify encryptionService.decrypt was called and threw
        verify(exactly = 1) { encryptionService.decrypt(testEncryptedData) }
    }

    // --- deleteCredential Tests ---
    @Test
    fun `deleteCredential should delete credential successfully when found`() = runTest {
        // Arrange
        // Configure apiSecretDao to return true for deletion success (as per DAO contract logic)
        coEvery { apiSecretDao.deleteSecret(testAlias) } returns true
        // Act
        val isDeleted = credentialManager.deleteCredential(testAlias)
        // Assert
        assertTrue(isDeleted, "deleteCredential should return true on successful deletion")
        // Verify apiSecretDao.deleteSecret was called correctly
        coVerify(exactly = 1) { apiSecretDao.deleteSecret(testAlias) }
        // Verify encryptionService was not called (delete doesn't involve encryption/decryption)
        verify { encryptionService wasNot Called }
    }
    @Test
    fun `deleteCredential should return true if alias is not found (already deleted state)`() = runTest {
        // Arrange
        val nonExistentAlias = "non-existent-alias"
        // Configure apiSecretDao to return true for deletion of a non-existent alias
        // (This matches the common DAO pattern where delete returns true if the item is not there or successfully removed)
        coEvery { apiSecretDao.deleteSecret(nonExistentAlias) } returns true
        // Act
        val isDeleted = credentialManager.deleteCredential(nonExistentAlias)
        // Assert
        assertTrue(isDeleted, "deleteCredential should return true even if the alias is not found")
        // Verify apiSecretDao.deleteSecret was called
        coVerify(exactly = 1) { apiSecretDao.deleteSecret(nonExistentAlias) }
        // Verify encryptionService was not called
        verify { encryptionService wasNot Called }
    }
    @Test
    fun `deleteCredential should return false if deleting from database fails (DAO returns false)`() = runTest {
        // Arrange
        // Configure apiSecretDao to return false, indicating a DB failure *handled by the DAO*
        coEvery { apiSecretDao.deleteSecret(testAlias) } returns false
        // Act
        val isDeleted = credentialManager.deleteCredential(testAlias)
        // Assert
        assertFalse(isDeleted, "deleteCredential should return false if the database deletion fails")
        // Verify apiSecretDao.deleteSecret was called
        coVerify(exactly = 1) { apiSecretDao.deleteSecret(testAlias) }
        // Verify encryptionService was not called
        verify { encryptionService wasNot Called }
    }
    @Test
    fun `deleteCredential should return false if database operation throws exception`() = runTest {
        // Arrange
        // Configure apiSecretDao to throw an exception during deletion
        val dbDeleteException = RuntimeException("DB Delete failed unexpectedly!")
        coEvery { apiSecretDao.deleteSecret(testAlias) } throws dbDeleteException
        // Act
        val isDeleted = credentialManager.deleteCredential(testAlias)
        // Assert
        assertFalse(isDeleted, "deleteCredential should return false if the database operation throws an exception")
        // Verify apiSecretDao.deleteSecret was called
        coVerify(exactly = 1) { apiSecretDao.deleteSecret(testAlias) }
        // Verify encryptionService was not called
        verify { encryptionService wasNot Called }
    }
}
```
```kotlin
// file: server/src/test/kotlin/eu/torvian/chatbot/server/service/security/EncryptionServiceTest.kt
package eu.torvian.chatbot.server.service.security
import eu.torvian.chatbot.server.domain.security.EncryptedSecret
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
/**
 * Unit tests for [EncryptionService].
 *
 * This test suite verifies that [EncryptionService] correctly orchestrates
 * the calls to the underlying [CryptoProvider] for encryption and decryption
 * operations, and correctly maps data to/from the [EncryptedSecret] object.
 *
 * The [CryptoProvider] dependency is mocked using MockK.
 */
class EncryptionServiceTest {
    private lateinit var cryptoProvider: CryptoProvider // The mocked CryptoProvider
    private lateinit var encryptionService: EncryptionService // The class under test
    // --- Test Data ---
    private val testPlaintext = "this is the secret data"
    private val mockGeneratedDEK = "mock-base64-generated-dek" // Mock DEK from provider
    private val mockEncryptedData = "mock-base64-encrypted-data" // Mock encrypted data from provider
    private val mockWrappedDEK = "mock-base64-wrapped-dek" // Mock wrapped DEK from provider
    private val mockKeyVersion = 5 // Mock key version from provider
    private val mockUnwrappedDEK = "mock-base64-unwrapped-dek" // Mock unwrapped DEK from provider
    // The EncryptedSecret structure that should be produced by encrypt
    private val expectedEncryptedSecret = EncryptedSecret(
        encryptedSecret = mockEncryptedData,
        encryptedDEK = mockWrappedDEK,
        keyVersion = mockKeyVersion
    )
    // An example EncryptedSecret structure that should be consumed by decrypt
    private val testEncryptedSecretInput = EncryptedSecret(
        encryptedSecret = "input-encrypted-data", // Can be different from mockEncryptedData
        encryptedDEK = "input-wrapped-dek",      // Can be different from mockWrappedDEK
        keyVersion = 99 // Can be different from mockKeyVersion (service doesn't use version for decrypt in current implementation)
    )

    @BeforeEach
    fun setUp() {
        // Create a mock of the CryptoProvider interface
        cryptoProvider = mockk<CryptoProvider>() // Explicit type is good practice
        // Create the EncryptionService instance with the mocked provider
        encryptionService = EncryptionService(cryptoProvider)
        // Note: No need to set up general mock behaviors here unless they apply to ALL tests.
        // Specific behaviors are set up within each test method.
    }
    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(cryptoProvider)
    }
    // --- encrypt Tests ---
    @Test
    fun `encrypt should correctly orchestrate encryption steps and return EncryptedSecret`() {
        // Arrange
        // Configure the mock CryptoProvider methods in the expected sequence
        every { cryptoProvider.generateDEK() } returns mockGeneratedDEK
        // Need to capture the DEK passed to encryptData and wrapDEK to ensure it's the one generated
        val dekSlotForDataEncrypt = slot<String>()
        every { cryptoProvider.encryptData(testPlaintext, capture(dekSlotForDataEncrypt)) } returns mockEncryptedData
        val dekSlotForWrap = slot<String>()
        every { cryptoProvider.wrapDEK(capture(dekSlotForWrap)) } returns mockWrappedDEK
        every { cryptoProvider.getKeyVersion() } returns mockKeyVersion
        // Act
        val result = encryptionService.encrypt(testPlaintext)
        // Assert
        // Verify the result matches the expected EncryptedSecret structure
        assertEquals(expectedEncryptedSecret, result, "Encryption result should match the expected EncryptedSecret")
        // Verify that the CryptoProvider methods were called in the correct sequence and with correct arguments
        // Use ordered = true to verify the call order
        verifyOrder {
            cryptoProvider.generateDEK()
            cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) // Assert using the *expected* value
            cryptoProvider.wrapDEK(mockGeneratedDEK)                   // Assert using the *expected* value
            cryptoProvider.getKeyVersion()
        }
        // Also verify that the same DEK generated was used for encryptData and wrapDEK
        assertEquals(mockGeneratedDEK, dekSlotForDataEncrypt.captured, "The DEK used for encryptData should be the one generated")
        assertEquals(mockGeneratedDEK, dekSlotForWrap.captured, "The DEK used for wrapDEK should be the one generated")
        // Confirm no other calls were made on the mock besides the ones explicitly verified above
        confirmVerified(cryptoProvider)
    }
    @Test
    fun `encrypt should propagate exception if generateDEK fails`() {
        // Arrange
        val generateDekException = RuntimeException("DEK generation failed")
        every { cryptoProvider.generateDEK() } throws generateDekException
        // Act & Assert
        // Assert that calling encrypt throws the expected exception
        val thrown = assertFailsWith<RuntimeException>(
            message = "encrypt should throw if generateDEK fails"
        ) {
            encryptionService.encrypt(testPlaintext)
        }
        assertEquals(generateDekException, thrown, "The thrown exception should be the one from generateDEK")
        // Verify only generateDEK was called
        verify(exactly = 1) { cryptoProvider.generateDEK() }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }
    @Test
    fun `encrypt should propagate exception if encryptData fails`() {
        // Arrange
        val encryptDataException = RuntimeException("Data encryption failed")
        every { cryptoProvider.generateDEK() } returns mockGeneratedDEK
        every { cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) } throws encryptDataException // Setup with expected args
        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "encrypt should throw if encryptData fails"
        ) {
            encryptionService.encrypt(testPlaintext)
        }
        assertEquals(encryptDataException, thrown, "The thrown exception should be the one from encryptData")
        // Verify generateDEK was called, and then encryptData was called (and threw)
        verifyOrder {
            cryptoProvider.generateDEK()
            cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK)
        }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }
    @Test
    fun `encrypt should propagate exception if wrapDEK fails`() {
        // Arrange
        val wrapDekException = RuntimeException("DEK wrapping failed")
        every { cryptoProvider.generateDEK() } returns mockGeneratedDEK
        every { cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) } returns mockEncryptedData
        every { cryptoProvider.wrapDEK(mockGeneratedDEK) } throws wrapDekException // Setup with expected args
        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "encrypt should throw if wrapDEK fails"
        ) {
            encryptionService.encrypt(testPlaintext)
        }
        assertEquals(wrapDekException, thrown, "The thrown exception should be the one from wrapDEK")
        // Verify generateDEK and encryptData were called, and then wrapDEK was called (and threw)
        verifyOrder {
            cryptoProvider.generateDEK()
            cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK)
            cryptoProvider.wrapDEK(mockGeneratedDEK)
        }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }
    @Test
    fun `encrypt should propagate exception if getKeyVersion fails`() {
        // Arrange
        val getKeyVersionException = RuntimeException("Get key version failed")
        every { cryptoProvider.generateDEK() } returns mockGeneratedDEK
        every { cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK) } returns mockEncryptedData
        every { cryptoProvider.wrapDEK(mockGeneratedDEK) } returns mockWrappedDEK
        every { cryptoProvider.getKeyVersion() } throws getKeyVersionException
        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "encrypt should throw if getKeyVersion fails"
        ) {
            encryptionService.encrypt(testPlaintext)
        }
        assertEquals(getKeyVersionException, thrown, "The thrown exception should be the one from getKeyVersion")
        // Verify all previous steps were called, and then getKeyVersion was called (and threw)
        verifyOrder {
            cryptoProvider.generateDEK()
            cryptoProvider.encryptData(testPlaintext, mockGeneratedDEK)
            cryptoProvider.wrapDEK(mockGeneratedDEK)
            cryptoProvider.getKeyVersion()
        }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }

    // --- decrypt Tests ---
    @Test
    fun `decrypt should correctly orchestrate decryption steps and return plaintext`() {
        // Arrange
        // Configure the mock CryptoProvider methods for decryption
        // Note: The input to unwrapDEK is from testEncryptedSecretInput
        every { cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK) } returns mockUnwrappedDEK
        // Note: The input to decryptData is from testEncryptedSecretInput (cipher) and the unwrapped DEK
        every { cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK) } returns testPlaintext
        // Act
        val resultPlaintext = encryptionService.decrypt(testEncryptedSecretInput)
        // Assert
        assertEquals(testPlaintext, resultPlaintext, "Decrypted result should match the original plaintext")
        // Verify that the CryptoProvider methods were called in the correct sequence and with correct arguments
        // Use ordered = true to verify the call order
        verifyOrder {
            cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK)
            cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK) // Assert using the *expected* value
        }
        // Confirm no other calls were made on the mock besides the ones explicitly verified above
        confirmVerified(cryptoProvider)
    }
    @Test
    fun `decrypt should propagate exception if unwrapDEK fails`() {
        // Arrange
        val unwrapDekException = RuntimeException("DEK unwrapping failed")
        every { cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK) } throws unwrapDekException
        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "decrypt should throw if unwrapDEK fails"
        ) {
            encryptionService.decrypt(testEncryptedSecretInput)
        }
        assertEquals(unwrapDekException, thrown, "The thrown exception should be the one from unwrapDEK")
        // Verify unwrapDEK was called (and threw)
        verify(exactly = 1) { cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK) }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }
    @Test
    fun `decrypt should propagate exception if decryptData fails`() {
        // Arrange
        val decryptDataException = RuntimeException("Data decryption failed")
        every { cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK) } returns mockUnwrappedDEK
        every { cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK) } throws decryptDataException // Setup with expected args
        // Act & Assert
        val thrown = assertFailsWith<RuntimeException>(
            message = "decrypt should throw if decryptData fails"
        ) {
            encryptionService.decrypt(testEncryptedSecretInput)
        }
        assertEquals(decryptDataException, thrown, "The thrown exception should be the one from decryptData")
        // Verify unwrapDEK was called, and then decryptData was called (and threw)
        verifyOrder {
            cryptoProvider.unwrapDEK(testEncryptedSecretInput.encryptedDEK)
            cryptoProvider.decryptData(testEncryptedSecretInput.encryptedSecret, mockUnwrappedDEK)
        }
        // Confirm no other calls were made
        confirmVerified(cryptoProvider)
    }
    // Note: The current decrypt implementation doesn't use keyVersion,
    // so there's no test needed for cryptoProvider.getKeyVersion() during decryption.
    // If the implementation were updated to, for example, select a KEK based on keyVersion,
    // you would add tests to cover that logic.
}
```
