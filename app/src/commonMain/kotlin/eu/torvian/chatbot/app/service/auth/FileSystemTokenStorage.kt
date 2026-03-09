package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.security.CryptoProvider
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

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
 * KMP-compatible implementation of [TokenStorage] using envelope encryption with multi-account support.
 *
 * This implementation manages multiple user accounts, storing each account's data in separate
 * subdirectories. It uses a two-file strategy per account for efficient key rotation:
 * - `{userId}/dek.json`: Stores the wrapped Data Encryption Key (DEK) and KEK version
 * - `{userId}/tokens.enc`: Stores the encrypted token data
 * - `active_account.json`: Tracks the currently active account
 *
 * The implementation supports:
 * - Storing multiple user accounts simultaneously
 * - Switching between accounts without re-authentication
 * - Active re-encryption when KEK versions change
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
        val user: User,
        val permissions: List<Permission> = emptyList(),
        val lastUsed: Instant = Clock.System.now()
    )

    private val storageDirPath = Path(storageDirectoryPath)
    private val activeAccountPath: Path = Path(storageDirPath, "active_account.json")

    // Public override functions

    override suspend fun saveAuthData(
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        user: User,
        permissions: List<Permission>
    ): Either<TokenStorageError, Unit> = either {
        catch({
            val userId = user.id

            val tokenData = TokenData(accessToken, refreshToken, expiresAt.epochSeconds, user, permissions, Clock.System.now())

            encryptAndSaveTokenData(userId, tokenData).bind()

            saveActiveUserId(userId).bind()

            logger.info("Saved auth data for user ${user.username} (ID: $userId)")
        }) { e: Exception ->
            when (e) {
                is SerializationException -> raise(TokenStorageError.InvalidTokenFormat(
                    "Failed to serialize auth data: ${e.message}", e
                ))
                else -> raise(TokenStorageError.IOError(
                    "Unexpected error during auth data save: ${e.message}", e
                ))
            }
        }
    }

    override suspend fun getAccountData(): Either<TokenStorageError, AccountData> =
        loadTokenData().map { tokenData -> AccountData(tokenData.user, tokenData.permissions, tokenData.lastUsed) }

    override suspend fun getAccountData(userId: Long): Either<TokenStorageError, AccountData> =
        loadTokenData(userId).map { tokenData -> AccountData(tokenData.user, tokenData.permissions, tokenData.lastUsed) }

    override suspend fun clearAuthData(): Either<TokenStorageError, Unit> = either {
        val activeUserId = getActiveUserIdOrRaise().bind()
        removeAccount(activeUserId).bind()
    }

    override suspend fun getAccessToken(): Either<TokenStorageError, String> =
        loadTokenData().map { it.accessToken }

    override suspend fun getRefreshToken(): Either<TokenStorageError, String> =
        loadTokenData().map { it.refreshToken }

    override suspend fun getExpiry(): Either<TokenStorageError, Instant> =
        loadTokenData().map { Instant.fromEpochSeconds(it.expiresAt) }

    override suspend fun listStoredAccounts(): Either<TokenStorageError, List<AccountData>> = either {
        catch({
            val accounts = mutableListOf<AccountData>()

            // Check if storage directory exists
            if (!fileSystem.exists(storageDirPath)) {
                return@catch accounts // Return empty list if no storage directory
            }

            // List all subdirectories in storageDirPath
            val children = fileSystem.list(storageDirPath)
            for (child in children) {
                val dirName = child.name
                val userId = dirName.toLongOrNull()
                if (userId != null) {
                    // Check if it's a valid user directory by checking if key and data files exist
                    val userKeyFilePath = getKeyFilePath(userId)
                    val userDataFilePath = getDataFilePath(userId)
                    if (fileSystem.exists(userKeyFilePath) && fileSystem.exists(userDataFilePath)) {
                        // Try to load token data for this user
                        loadTokenData(userId).fold(
                            ifLeft = {
                                logger.warn("Failed to load token data for user $userId during account listing. Skipping.")
                            },
                            ifRight = { tokenData ->
                                accounts.add(
                                    AccountData(
                                        user = tokenData.user,
                                        permissions = tokenData.permissions,
                                        lastUsed = tokenData.lastUsed
                                    )
                                )
                            }
                        )
                    }
                }
            }

            // Sort accounts by last used
            accounts.sortedByDescending { it.lastUsed }
        }) { e: Exception ->
            raise(TokenStorageError.IOError("Failed to list stored accounts: ${e.message}", e))
        }
    }

    override suspend fun switchAccount(userId: Long): Either<TokenStorageError, Unit> = either {
        // Verify the account has stored data
        val userKeyFilePath = getKeyFilePath(userId)
        val userDataFilePath = getDataFilePath(userId)
        ensure(fileSystem.exists(userKeyFilePath) && fileSystem.exists(userDataFilePath)) {
            TokenStorageError.NotFound("Token data not found for user $userId")
        }

        // Update lastUsed for this account
        val tokenData = loadTokenData(userId).bind()
        val updatedTokenData = tokenData.copy(lastUsed = Clock.System.now())
        encryptAndSaveTokenData(userId, updatedTokenData).bind()

        // Update the active user ID
        saveActiveUserId(userId).bind()

        logger.info("Switched to account with userId: $userId")
    }

    override suspend fun getCurrentAccountId(): Either<TokenStorageError, Long?> = loadActiveUserId()

    override suspend fun removeAccount(userId: Long): Either<TokenStorageError, Unit> = either {
        catch({
            // If this is the active account, set active to null
            val currentActive = loadActiveUserId().bind()
            if (currentActive == userId) {
                saveActiveUserId(null).bind()
            }

            // Delete the user's directory and files
            val userDir = getUserDirectory(userId)
            if (fileSystem.exists(userDir)) {
                // Delete files first
                val keyFile = getKeyFilePath(userId)
                val dataFile = getDataFilePath(userId)
                if (fileSystem.exists(keyFile)) {
                    fileSystem.delete(keyFile)
                }
                if (fileSystem.exists(dataFile)) {
                    fileSystem.delete(dataFile)
                }
                // Delete directory
                fileSystem.delete(userDir)
            }

            logger.info("Removed account with userId: $userId")
        }) { e: Exception ->
            raise(TokenStorageError.IOError("Failed to remove account: ${e.message}", e))
        }
    }

    // Protected functions

    /**
     * Gets the key file path for a specific user account.
     *
     * @param userId The ID of the user account
     * @return The path to the user's key metadata file
     */
    protected open fun getKeyFilePath(userId: Long): Path {
        return Path(getUserDirectory(userId), "dek.json")
    }

    /**
     * Gets the data file path for a specific user account.
     *
     * @param userId The ID of the user account
     * @return The path to the user's encrypted data file
     */
    protected open fun getDataFilePath(userId: Long): Path {
        return Path(getUserDirectory(userId), "tokens.enc")
    }

    // Private functions

    /**
     * Gets the directory path for a specific user account.
     *
     * @param userId The ID of the user account
     * @return The path to the user's storage directory
     */
    private fun getUserDirectory(userId: Long): Path {
        return Path(storageDirPath, userId.toString())
    }

    /**
     * Loads the currently active user ID from disk.
     *
     * @return Either a [TokenStorageError] on failure or the active user ID (null if none) on success
     */
    private fun loadActiveUserId(): Either<TokenStorageError, Long?> = either {
        catch({
            if (!fileSystem.exists(activeAccountPath)) {
                return@either null
            }

            val activeJson = fileSystem.source(activeAccountPath).buffered().use { source ->
                source.readString()
            }

            catch({
                json.decodeFromString<Long?>(activeJson)
            }) { e: SerializationException ->
                raise(TokenStorageError.InvalidTokenFormat("Failed to parse active account", e))
            }
        }) { e: Exception ->
            when (e) {
                is kotlinx.io.IOException -> raise(TokenStorageError.IOError("Failed to read active account", e))
                else -> raise(TokenStorageError.IOError("Unexpected error reading active account", e))
            }
        }
    }

    /**
     * Saves the active user ID to disk.
     *
     * @param activeUserId The active user ID to save, or null if none
     * @return Either a [TokenStorageError] on failure or Unit on success
     */
    private fun saveActiveUserId(activeUserId: Long?): Either<TokenStorageError, Unit> = either {
        catch({
            fileSystem.createDirectories(storageDirPath)

            val activeJson = json.encodeToString<Long?>(activeUserId)

            fileSystem.sink(activeAccountPath).buffered().use { sink ->
                sink.writeString(activeJson)
            }
            setSecureFilePermissions(activeAccountPath)
        }) { e: Exception ->
            when (e) {
                is SerializationException -> raise(TokenStorageError.InvalidTokenFormat(
                    "Failed to serialize active account: ${e.message}", e
                ))
                else -> raise(TokenStorageError.IOError(
                    "Failed to save active account: ${e.message}", e
                ))
            }
        }
    }

    /**
     * Gets the currently active user ID or raises an error if none is set.
     *
     * @return Either a [TokenStorageError] if no active account exists or the active user ID
     */
    private fun getActiveUserIdOrRaise(): Either<TokenStorageError, Long> = either {
        val activeUserId = loadActiveUserId().bind()
        activeUserId ?: raise(TokenStorageError.NotFound("No active account set"))
    }

    /**
     * Loads token data for a specific user account.
     *
     * @param userId The ID of the user account to load, or null to load the active account
     * @return Either a [TokenStorageError] on failure or the token data on success
     */
    private suspend fun loadTokenData(userId: Long? = null): Either<TokenStorageError, TokenData> = either {
        catch({
            // Get the user ID to load (either specified or active)
            val targetUserId = userId ?: getActiveUserIdOrRaise().bind()

            val userKeyFilePath = getKeyFilePath(targetUserId)
            val userDataFilePath = getDataFilePath(targetUserId)

            ensure(fileSystem.exists(userKeyFilePath) && fileSystem.exists(userDataFilePath)) {
                TokenStorageError.NotFound("Token storage files not found for user $targetUserId")
            }

            // 1. Read metadata and encrypted data.
            val metadataJson = fileSystem.source(userKeyFilePath).buffered().use { source ->
                source.readString()
            }
            val dekMetadata = catch({
                json.decodeFromString<DekMetadata>(metadataJson)
            }) { e: SerializationException ->
                raise(TokenStorageError.InvalidTokenFormat("Failed to parse stored token metadata", e))
            }
            val encryptedData = fileSystem.source(userDataFilePath).buffered().use { source ->
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
                performActiveDekReEncryption(dek, dekMetadata.kekVersion, currentKekVersion, targetUserId)
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
     *
     * @param dek The plaintext Data Encryption Key
     * @param oldKekVersion The old Key Encryption Key version
     * @param newKekVersion The new Key Encryption Key version
     * @param userId The ID of the user account being re-encrypted
     */
    private suspend fun performActiveDekReEncryption(
        dek: String,
        oldKekVersion: Int,
        newKekVersion: Int,
        userId: Long
    ) {
        logger.info("Performing active DEK re-encryption for user $userId. Migrating from KEK version $oldKekVersion to $newKekVersion.")

        either {
            catch({
                val newWrappedDek = cryptoProvider.wrapDEK(dek).bind()
                val newDekMetadata = DekMetadata(newWrappedDek, newKekVersion)
                val newMetadataJson = json.encodeToString(DekMetadata.serializer(), newDekMetadata)

                val userKeyFilePath = getKeyFilePath(userId)
                fileSystem.sink(userKeyFilePath).buffered().use { sink ->
                    sink.writeString(newMetadataJson)
                }
                setSecureFilePermissions(userKeyFilePath)
            }) { e: Exception ->
                // A failure here should not fail the primary read operation.
                // The system will try again on the next read.
                logger.error("Failed to perform active DEK re-encryption for user $userId", e)
                raise(Unit) // Convert to a harmless error that gets ignored
            }
        }.onLeft { error ->
            logger.error("Failed to wrap DEK during re-encryption: $error")
        }
    }

    /**
     * Encrypts and saves token data for a specific user account.
     *
     * @param userId The ID of the user account
     * @param tokenData The token data to save
     * @return Either a [TokenStorageError] on failure or Unit on success
     */
    private suspend fun encryptAndSaveTokenData(userId: Long, tokenData: TokenData): Either<TokenStorageError, Unit> = either {
        catch({
            val plainTextJson = json.encodeToString(TokenData.serializer(), tokenData)

            // Generate DEK, encrypt data, and wrap DEK
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

            // Write files
            val userDir = getUserDirectory(userId)
            fileSystem.createDirectories(userDir)

            val userDataFilePath = getDataFilePath(userId)
            val userKeyFilePath = getKeyFilePath(userId)

            fileSystem.sink(userDataFilePath).buffered().use { sink ->
                sink.writeString(encryptedData)
            }
            setSecureFilePermissions(userDataFilePath)

            fileSystem.sink(userKeyFilePath).buffered().use { sink ->
                sink.writeString(metadataJson)
            }
            setSecureFilePermissions(userKeyFilePath)
        }) { e: Exception ->
            when (e) {
                is SerializationException -> raise(TokenStorageError.InvalidTokenFormat(
                    "Failed to serialize token data: ${e.message}", e
                ))
                else -> raise(TokenStorageError.IOError(
                    "Unexpected error during token data save: ${e.message}", e
                ))
            }
        }
    }
}
