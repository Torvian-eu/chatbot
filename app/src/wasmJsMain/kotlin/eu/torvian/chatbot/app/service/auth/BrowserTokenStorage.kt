package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.security.CryptoProvider
import kotlinx.browser.localStorage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.w3c.dom.Storage
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Browser localStorage-backed implementation of [TokenStorage] using envelope encryption with
 * multi-account support.
 *
 * This implementation mirrors [FileSystemTokenStorage] but uses the browser's `localStorage`
 * as the persistence layer instead of the file system. Each logical "file" maps to a single
 * localStorage entry, namespaced by [storageNamespace]:
 *
 * - `"{namespace}/{userId}/dek"` — the wrapped DEK and KEK version (JSON)
 * - `"{namespace}/{userId}/data"` — the encrypted token data (Base64 ciphertext)
 * - `"{namespace}/active_account"` — the currently active user ID (JSON long or null)
 *
 * @param cryptoProvider The provider for all cryptographic operations.
 * @param storageNamespace A namespace prefix used to scope localStorage keys
 *   (e.g., `"eu.torvian.chatbot/tokens"`).
 * @param storage The Web Storage instance to use. Defaults to [localStorage].
 * @param json The JSON serializer instance.
 */
class BrowserTokenStorage(
    private val cryptoProvider: CryptoProvider,
    private val storageNamespace: String,
    private val storage: Storage = localStorage,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : TokenStorage {

    private val logger = kmpLogger<BrowserTokenStorage>()

    // -----------------------
    // Key helpers
    // -----------------------

    private fun dekKey(userId: Long): String = "$storageNamespace/$userId/dek"
    private fun dataKey(userId: Long): String = "$storageNamespace/$userId/data"
    private val activeAccountKey: String = "$storageNamespace/active_account"

    // -----------------------
    // Public interface
    // -----------------------

    override suspend fun saveAuthData(
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        user: User,
        permissions: List<Permission>
    ): Either<TokenStorageError, Unit> = either {
        catch({
            val userId = user.id
            val tokenData = TokenData(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt.epochSeconds,
                user = user,
                permissions = permissions,
                lastUsed = Clock.System.now()
            )

            encryptAndSaveTokenData(userId, tokenData).bind()
            saveActiveUserId(userId).bind()

            logger.info("Saved auth data for user ${user.username} (ID: $userId)")
        }) { e: Exception ->
            when (e) {
                is SerializationException -> raise(
                    TokenStorageError.InvalidTokenFormat("Failed to serialize auth data: ${e.message}", e)
                )

                else -> raise(
                    TokenStorageError.IOError("Unexpected error during auth data save: ${e.message}", e)
                )
            }
        }
    }

    override suspend fun getAccountData(): Either<TokenStorageError, AccountData> =
        loadTokenData().map { AccountData(it.user, it.permissions, it.lastUsed) }

    override suspend fun getAccountData(userId: Long): Either<TokenStorageError, AccountData> =
        loadTokenData(userId).map { AccountData(it.user, it.permissions, it.lastUsed) }

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

            // Scan all localStorage keys for user DEK entries in our namespace.
            for (i in 0 until storage.length) {
                val key = storage.key(i) ?: continue
                // Match keys of the form "{namespace}/{userId}/dek"
                val prefix = "$storageNamespace/"
                val suffix = "/dek"
                if (!key.startsWith(prefix) || !key.endsWith(suffix)) continue

                val middle = key.removePrefix(prefix).removeSuffix(suffix)
                val userId = middle.toLongOrNull() ?: continue

                // Verify the data entry also exists.
                if (storage.getItem(dataKey(userId)) == null) continue

                loadTokenData(userId).fold(
                    ifLeft = {
                        logger.warn("Failed to load token data for user $userId during account listing. Skipping.")
                    },
                    ifRight = { tokenData ->
                        accounts.add(AccountData(tokenData.user, tokenData.permissions, tokenData.lastUsed))
                    }
                )
            }

            accounts.sortedByDescending { it.lastUsed }
        }) { e: Exception ->
            raise(TokenStorageError.IOError("Failed to list stored accounts: ${e.message}", e))
        }
    }

    override suspend fun switchAccount(userId: Long): Either<TokenStorageError, Unit> = either {
        ensure(storage.getItem(dekKey(userId)) != null && storage.getItem(dataKey(userId)) != null) {
            TokenStorageError.NotFound("Token data not found for user $userId")
        }

        // Update lastUsed for this account.
        val tokenData = loadTokenData(userId).bind()
        val updatedTokenData = tokenData.copy(lastUsed = Clock.System.now())
        encryptAndSaveTokenData(userId, updatedTokenData).bind()

        saveActiveUserId(userId).bind()

        logger.info("Switched to account with userId: $userId")
    }

    override suspend fun getCurrentAccountId(): Either<TokenStorageError, Long?> = loadActiveUserId()

    override suspend fun removeAccount(userId: Long): Either<TokenStorageError, Unit> = either {
        catch({
            // If this is the active account, clear the active pointer.
            val currentActive = loadActiveUserId().bind()
            if (currentActive == userId) {
                saveActiveUserId(null).bind()
            }

            storage.removeItem(dekKey(userId))
            storage.removeItem(dataKey(userId))

            logger.info("Removed account with userId: $userId")
        }) { e: Exception ->
            raise(TokenStorageError.IOError("Failed to remove account: ${e.message}", e))
        }
    }

    // -----------------------
    // Private helpers
    // -----------------------

    private fun loadActiveUserId(): Either<TokenStorageError, Long?> = either {
        catch({
            val raw = storage.getItem(activeAccountKey)
                ?: return@either null

            catch({
                json.decodeFromString<Long?>(raw)
            }) { e: SerializationException ->
                raise(TokenStorageError.InvalidTokenFormat("Failed to parse active account", e))
            }
        }) { e: Exception ->
            raise(TokenStorageError.IOError("Unexpected error reading active account: ${e.message}", e))
        }
    }

    private fun saveActiveUserId(activeUserId: Long?): Either<TokenStorageError, Unit> = either {
        catch({
            val raw = json.encodeToString<Long?>(activeUserId)
            storage.setItem(activeAccountKey, raw)
        }) { e: Exception ->
            when (e) {
                is SerializationException -> raise(
                    TokenStorageError.InvalidTokenFormat("Failed to serialize active account: ${e.message}", e)
                )

                else -> raise(
                    TokenStorageError.IOError("Failed to save active account: ${e.message}", e)
                )
            }
        }
    }

    private fun getActiveUserIdOrRaise(): Either<TokenStorageError, Long> = either {
        val activeUserId = loadActiveUserId().bind()
        activeUserId ?: raise(TokenStorageError.NotFound("No active account set"))
    }

    /**
     * Loads and decrypts token data for the given user, or the currently active user if [userId]
     * is null.
     */
    private suspend fun loadTokenData(userId: Long? = null): Either<TokenStorageError, TokenData> = either {
        catch({
            val targetUserId = userId ?: getActiveUserIdOrRaise().bind()

            val metadataJson = storage.getItem(dekKey(targetUserId))
            val encryptedData = storage.getItem(dataKey(targetUserId))

            ensure(metadataJson != null && encryptedData != null) {
                TokenStorageError.NotFound("Token storage entries not found for user $targetUserId")
            }

            val dekMetadata = catch({
                json.decodeFromString<DekMetadata>(metadataJson)
            }) { e: SerializationException ->
                raise(TokenStorageError.InvalidTokenFormat("Failed to parse stored token metadata", e))
            }

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

            // Active re-encryption if the KEK version has changed.
            val currentKekVersion = cryptoProvider.getKeyVersion()
            if (dekMetadata.kekVersion != currentKekVersion) {
                performActiveDekReEncryption(dek, dekMetadata.kekVersion, currentKekVersion, targetUserId)
            }

            tokenData
        }) { e: Exception ->
            raise(TokenStorageError.EncryptionError("Failed to decrypt token data: ${e.message}", e))
        }
    }

    /**
     * Re-wraps the provided plaintext DEK with the current KEK and persists the new metadata.
     * This is a "self-healing" mechanism to migrate keys over time. Failures here do not
     * propagate to the caller.
     */
    private suspend fun performActiveDekReEncryption(
        dek: String,
        oldKekVersion: Int,
        newKekVersion: Int,
        userId: Long
    ) {
        logger.info(
            "Performing active DEK re-encryption for user $userId. " +
                    "Migrating from KEK version $oldKekVersion to $newKekVersion."
        )

        either {
            catch({
                val newWrappedDek = cryptoProvider.wrapDEK(dek).bind()
                val newDekMetadata = DekMetadata(newWrappedDek, newKekVersion)
                val newMetadataJson = json.encodeToString(DekMetadata.serializer(), newDekMetadata)
                storage.setItem(dekKey(userId), newMetadataJson)
            }) { e: Exception ->
                logger.error("Failed to perform active DEK re-encryption for user $userId: ${e.message}", e)
                raise(Unit)
            }
        }.onLeft {
            logger.error("Failed to wrap DEK during re-encryption for user $userId")
        }
    }

    /**
     * Encrypts [tokenData] and persists both the wrapped DEK metadata and the ciphertext to
     * localStorage.
     */
    private suspend fun encryptAndSaveTokenData(
        userId: Long,
        tokenData: TokenData
    ): Either<TokenStorageError, Unit> = either {
        catch({
            val plainTextJson = json.encodeToString(TokenData.serializer(), tokenData)

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

            storage.setItem(dataKey(userId), encryptedData)
            storage.setItem(dekKey(userId), metadataJson)
        }) { e: Exception ->
            when (e) {
                is SerializationException -> raise(
                    TokenStorageError.InvalidTokenFormat("Failed to serialize token data: ${e.message}", e)
                )

                else -> raise(
                    TokenStorageError.IOError("Unexpected error during token data save: ${e.message}", e)
                )
            }
        }
    }
}


