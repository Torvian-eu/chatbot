package eu.torvian.chatbot.worker.setup

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * [SecretsStore] implementation that persists secrets as JSON on the local filesystem.
 *
 * Reads are performed directly. Writes use an atomic temp-file-and-move strategy
 * to minimise the risk of corrupted secrets files.
 *
 * @property json JSON serializer used for encoding/decoding secrets.
 */
class FileSecretsStore(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
) : SecretsStore {

    override suspend fun read(path: Path): Either<SecretsStoreError, Secrets> =
        withContext(Dispatchers.IO) {
            try {
                if (!Files.exists(path)) {
                    SecretsStoreError.NotFound(path.toString()).left()
                } else {
                    val value = json.decodeFromString<Secrets>(Files.readString(path))
                    value.right()
                }
            } catch (e: SerializationException) {
                SecretsStoreError.Invalid(path.toString(), e.message ?: "Invalid secrets JSON").left()
            } catch (e: Exception) {
                SecretsStoreError.ReadFailed(path.toString(), e.message ?: e::class.simpleName.orEmpty()).left()
            }
        }

    override suspend fun write(path: Path, secrets: Secrets): Either<SecretsStoreError, Unit> =
        withContext(Dispatchers.IO) {
            var tempFile: Path? = null
            try {
                val targetPath = path.toAbsolutePath().normalize()
                val targetDirectory = targetPath.parent
                    ?: return@withContext SecretsStoreError.WriteFailed(
                        targetPath.toString(),
                        "Unable to resolve parent directory"
                    ).left()

                Files.createDirectories(targetDirectory)
                tempFile = Files.createTempFile(targetDirectory, "${targetPath.fileName}.", ".tmp")
                Files.writeString(tempFile, json.encodeToString(Secrets.serializer(), secrets))

                try {
                    Files.move(
                        tempFile,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }

                Unit.right()
            } catch (e: Exception) {
                SecretsStoreError.WriteFailed(path.toString(), e.message ?: e::class.simpleName.orEmpty()).left()
            } finally {
                if (tempFile != null && Files.exists(tempFile)) {
                    runCatching { Files.delete(tempFile) }
                }
            }
        }
}
