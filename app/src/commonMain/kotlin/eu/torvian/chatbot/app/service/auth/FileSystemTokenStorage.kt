package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.security.CryptoProvider
import kotlinx.datetime.Instant
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A serializable container for the DEK metadata.
 * This is stored separately from the encrypted data.
 */
@Serializable
private data class DekMetadata(
    val wrappedDek: String,    // Base64 encoded wrapped Data Encryption Key
    val kekVersion: Int        // Version of the Key Encryption Key used
)

/**
 * KMP-compatible implementation of [TokenStorage] using envelope encryption with a two-file strategy.
 *
 * This implementation separates key metadata from encrypted data for more efficient
 * key rotation. It manages two files in the provided [storageDirectoryPath]:
 * - `dek.json`: Stores the wrapped Data Encryption Key (DEK) and the version of the
 *   Key Encryption Key (KEK) used to wrap it.
 * - `tokens.enc`: Stores the actual token data, encrypted with the DEK.
 *
 * It also implements **active re-encryption**: if data encrypted with an old KEK is
 * read, the DEK is automatically re-wrapped with the current KEK.
 *
 * @param cryptoProvider The provider for all cryptographic operations.
 * @param storageDirectoryPath The absolute path to the directory for storing the files.
 * @param fileSystem The KMP FileSystem to use. Defaults to the system's default file system.
 * @param json The JSON serializer instance.
 */
open class FileSystemTokenStorage(
    private val cryptoProvider: CryptoProvider,
    storageDirectoryPath: String,
    private val fileSystem: FileSystem = SystemFileSystem,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : TokenStorage {
    private val logger = kmpLogger<FileSystemTokenStorage>()

    @Serializable
    private data class TokenData(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val user: User
    )

    private val storageDirPath = Path(storageDirectoryPath)
    protected open val keyFilePath: Path = Path(storageDirPath, "dek.json")
    protected open val dataFilePath: Path = Path(storageDirPath, "tokens.enc")

    override suspend fun saveAuthData(
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        user: User
    ): Either<TokenStorageError, Unit> = either {
        catch({
            // 1. Prepare the plaintext token data with user info.
            val tokenData = TokenData(accessToken, refreshToken, expiresAt.epochSeconds, user)
            val plainTextJson = json.encodeToString(TokenData.serializer(), tokenData)

            // 2-4. Generate DEK, encrypt data, and wrap DEK using bind for sequential operations
            val dek = cryptoProvider.generateDEK()
                .mapLeft { TokenStorageError.EncryptionError("Failed to generate DEK: ${it.message}", it.cause) }
                .bind()

            val encryptedData = cryptoProvider.encryptData(plainTextJson, dek)
                .mapLeft { TokenStorageError.EncryptionError("Failed to encrypt data: ${it.message}", it.cause) }
                .bind()

            val wrappedDek = cryptoProvider.wrapDEK(dek)
                .mapLeft { TokenStorageError.EncryptionError("Failed to wrap DEK: ${it.message}", it.cause) }
                .bind()

            val kekVersion = cryptoProvider.getKeyVersion()
            val dekMetadata = DekMetadata(wrappedDek, kekVersion)
            val metadataJson = json.encodeToString(DekMetadata.serializer(), dekMetadata)

            // 5. Write both files to disk with secure permissions.
            fileSystem.createDirectories(storageDirPath)

            // Write encrypted data file
            fileSystem.sink(dataFilePath).buffered().use { sink ->
                sink.writeString(encryptedData)
            }
            setSecureFilePermissions(dataFilePath)

            // Write key metadata file
            fileSystem.sink(keyFilePath).buffered().use { sink ->
                sink.writeString(metadataJson)
            }
            setSecureFilePermissions(keyFilePath)
        }) { e: Exception ->
            when (e) {
                is SerializationException -> TokenStorageError.InvalidTokenFormat(
                    "Failed to serialize auth data: ${e.message}", e
                )
                else -> TokenStorageError.IOError(
                    "Unexpected error during auth data save: ${e.message}", e
                )
            }
        }
    }

    override suspend fun getUserData(): Either<TokenStorageError, User> =
        loadTokenData().map { it.user }

    override suspend fun clearAuthData(): Either<TokenStorageError, Unit> = either {
        catch({
            if (fileSystem.exists(dataFilePath)) {
                fileSystem.delete(dataFilePath)
            }
            if (fileSystem.exists(keyFilePath)) {
                fileSystem.delete(keyFilePath)
            }
        }) { e: Exception ->
            TokenStorageError.IOError(
                "Failed to clear auth data: ${e.message}", e
            )
        }
    }

    override suspend fun getAccessToken(): Either<TokenStorageError, String> =
        loadTokenData().map { it.accessToken }

    override suspend fun getRefreshToken(): Either<TokenStorageError, String> =
        loadTokenData().map { it.refreshToken }

    override suspend fun getExpiry(): Either<TokenStorageError, Instant> =
        loadTokenData().map { Instant.fromEpochSeconds(it.expiresAt) }

    private suspend fun loadTokenData(): Either<TokenStorageError, TokenData> = either {
        catch({
            ensure(fileSystem.exists(keyFilePath) && fileSystem.exists(dataFilePath)) {
                TokenStorageError.NotFound("Token storage files not found")
            }

            // 1. Read metadata and encrypted data.
            val metadataJson = fileSystem.source(keyFilePath).buffered().use { source ->
                source.readString()
            }
            val dekMetadata = catch({
                json.decodeFromString<DekMetadata>(metadataJson)
            }) { e: SerializationException ->
                raise(TokenStorageError.InvalidTokenFormat("Failed to parse stored token metadata", e))
            }
            val encryptedData = fileSystem.source(dataFilePath).buffered().use { source ->
                source.readString()
            }

            // 2-3. Unwrap DEK and decrypt data using bind for sequential operations
            val dek = cryptoProvider.unwrapDEK(dekMetadata.wrappedDek, dekMetadata.kekVersion)
                .mapLeft { TokenStorageError.EncryptionError("Failed to unwrap DEK: ${it.message}", it.cause) }
                .bind()

            val decryptedJson = cryptoProvider.decryptData(encryptedData, dek)
                .mapLeft { TokenStorageError.EncryptionError("Failed to decrypt data: ${it.message}", it.cause) }
                .bind()

            val tokenData = catch({
                json.decodeFromString<TokenData>(decryptedJson)
            }) { e: SerializationException ->
                raise(TokenStorageError.InvalidTokenFormat("Failed to parse decrypted token data", e))
            }

            // 4. Active re-encryption if needed
            val currentKekVersion = cryptoProvider.getKeyVersion()
            if (dekMetadata.kekVersion != currentKekVersion) {
                performActiveDekReEncryption(dek, dekMetadata.kekVersion, currentKekVersion)
            }

            tokenData
        }) { e: Exception ->
            when (e) {
                is kotlinx.io.IOException -> raise(TokenStorageError.IOError("Failed to read tokens from file", e))
                else -> raise(TokenStorageError.EncryptionError("Failed to decrypt token data", e))
            }
        }
    }

    /**
     * Re-wraps the provided plaintext DEK with the current KEK and overwrites the metadata file.
     * This is a "self-healing" mechanism to migrate keys over time.
     */
    private suspend fun performActiveDekReEncryption(dek: String, oldKekVersion: Int, newKekVersion: Int) {
        logger.info("Performing active DEK re-encryption. Migrating from KEK version $oldKekVersion to $newKekVersion.")

        either {
            catch({
                val newWrappedDek = cryptoProvider.wrapDEK(dek).bind()
                val newDekMetadata = DekMetadata(newWrappedDek, newKekVersion)
                val newMetadataJson = json.encodeToString(DekMetadata.serializer(), newDekMetadata)

                fileSystem.sink(keyFilePath).buffered().use { sink ->
                    sink.writeString(newMetadataJson)
                }
                setSecureFilePermissions(keyFilePath)
            }) { e: Exception ->
                // A failure here should not fail the primary read operation.
                // The system will try again on the next read.
                logger.error("Failed to perform active DEK re-encryption", e)
                raise(Unit) // Convert to a harmless error that gets ignored
            }
        }.onLeft { error ->
            logger.error("Failed to wrap DEK during re-encryption: $error")
        }
    }
}
