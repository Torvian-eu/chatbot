package eu.torvian.chatbot.worker.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File-backed implementation of [ServiceTokenStore].
 *
 * This store persists the latest worker access token so the worker can attempt a
 * fast path on startup before falling back to the certificate challenge flow.
 *
 * @property filePath Path to the token cache JSON file.
 */
class FileServiceTokenStore(
    private val filePath: Path
) : ServiceTokenStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override suspend fun load(): Either<ServiceTokenStoreError, StoredServiceToken?> {
        return withContext(Dispatchers.IO) {
            if (!Files.exists(filePath)) {
                logger.debug("No cached worker token found at {}", filePath)
                return@withContext null.right()
            }

            try {
                logger.debug("Loading cached worker token from {}", filePath)
                json.decodeFromString<StoredServiceToken>(Files.readString(filePath)).right()
            } catch (e: SerializationException) {
                logger.warn("Worker token cache is corrupt at {}", filePath)
                ServiceTokenStoreError.TokenCacheCorrupt(filePath.toString(), e.message ?: "Invalid JSON").left()
            } catch (e: Exception) {
                logger.warn("Failed to read worker token cache from {}", filePath, e)
                ServiceTokenStoreError.TokenCacheReadFailed(filePath.toString(), e.message ?: e::class.simpleName.orEmpty()).left()
            }
        }
    }

    /**
     * Persists the token using a write-then-move strategy to reduce risk of partial writes.
     *
     * The flow is:
     * 1. Resolve a stable absolute target path.
     * 2. Write JSON to a temp file in the same directory.
     * 3. Move temp file into place with atomic move when available.
     * 4. Fall back to replace move when atomic move is unsupported.
     * 5. Best-effort cleanup of leftover temp file on failures.
     */
    override suspend fun save(token: StoredServiceToken): Either<ServiceTokenStoreError, Unit> {
        return withContext(Dispatchers.IO) {
            var tempFile: Path? = null
            try {
                // Use one normalized absolute path for directory creation and final move target.
                val targetPath = filePath.toAbsolutePath().normalize()
                val targetDirectory =
                    targetPath.parent ?: return@withContext ServiceTokenStoreError.TokenCacheWriteFailed(
                        targetPath.toString(),
                        "Unable to resolve token cache directory"
                    ).left()

                Files.createDirectories(targetDirectory)
                // Temp file must live in the same directory to keep move semantics predictable.
                tempFile = Files.createTempFile(targetDirectory, "${targetPath.fileName}.", ".tmp")

                logger.debug("Persisting worker token cache to {}", targetPath)
                Files.writeString(tempFile, json.encodeToString(token))

                try {
                    // Prefer atomic replace when the filesystem supports it.
                    Files.move(
                        tempFile,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    // Fallback keeps behavior consistent on filesystems without atomic move support.
                    logger.debug("Atomic move is not supported for {}; using replace move fallback", targetPath)
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }

                Unit.right()
            } catch (e: Exception) {
                val targetPath = filePath.toAbsolutePath().normalize()
                logger.warn("Failed to persist worker token cache to {}", targetPath, e)
                ServiceTokenStoreError.TokenCacheWriteFailed(targetPath.toString(), e.message ?: e::class.simpleName.orEmpty()).left()
            } finally {
                if (tempFile != null && Files.exists(tempFile)) {
                    // Cleanup is best-effort so primary save errors are not masked.
                    runCatching { Files.delete(tempFile) }
                        .onFailure { cleanupError -> logger.debug("Failed to clean up temp token file {}", tempFile, cleanupError) }
                }
            }
        }
    }

    override suspend fun clear(): Either<ServiceTokenStoreError, Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (Files.exists(filePath)) {
                    logger.debug("Deleting worker token cache at {}", filePath)
                    Files.delete(filePath)
                }
                Unit.right()
            } catch (e: Exception) {
                logger.warn("Failed to delete worker token cache at {}", filePath, e)
                ServiceTokenStoreError.TokenCacheDeleteFailed(filePath.toString(), e.message ?: e::class.simpleName.orEmpty()).left()
            }
        }
    }

    companion object {
        private val logger: Logger = LogManager.getLogger(FileServiceTokenStore::class.java)
    }
}

