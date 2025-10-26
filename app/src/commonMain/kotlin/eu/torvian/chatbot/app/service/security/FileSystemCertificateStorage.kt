package eu.torvian.chatbot.app.service.security

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.app.service.auth.setSecureFilePermissions
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class StoredCertificateData(
    val pem: String,
    val fingerprint: String
)

/**
 * KMP-compatible implementation of [CertificateStorage].
 *
 * For each trusted server URL, it creates a subdirectory containing a single file:
 * - `certificate.json`: Stores the certificate PEM and fingerprint in JSON format.
 *
 * File operations use the provided [FileSystem] (defaults to [SystemFileSystem])
 * which keeps the code KMP-friendly.
 *
 * Security considerations:
 * - `setSecureFilePermissions` is called after file creation on platforms that support it.
 *
 * @param storageDirectoryPath Absolute path to the directory used for certificate storage.
 * @param fileSystem The KMP FileSystem instance to use (defaults to the system file system).
 * @param json JSON instance used for serialization/deserialization.
 */
class FileSystemCertificateStorage(
    storageDirectoryPath: String,
    private val fileSystem: FileSystem = SystemFileSystem,
    private val json: Json = Json
) : CertificateStorage {

    private val logger = createKmpLogger("FileSystemCertificateStorage")
    private val storageDirPath = Path(storageDirectoryPath)

    override suspend fun storeCertificate(
        serverUrl: String,
        certificatePem: String,
        fingerprint: String
    ): Either<CertificateStorageError, Unit> = either {
        catch({
            val dataToStore = StoredCertificateData(certificatePem, fingerprint)
            val plainTextJson = json.encodeToString(StoredCertificateData.serializer(), dataToStore)

            val serverDir = getServerDirectory(serverUrl)
            fileSystem.createDirectories(serverDir) // Ensure directory exists

            val certDataFilePath = getCertDataFilePath(serverUrl)

            fileSystem.sink(certDataFilePath).buffered().use { it.writeString(plainTextJson) }
            setSecureFilePermissions(certDataFilePath) // Apply secure permissions

            logger.info("Successfully stored certificate for $serverUrl")
        }) { e ->
            logger.error("Failed to store certificate for $serverUrl", e)
            raise(mapExceptionToError(e, "Failed to store certificate"))
        }
    }

    override suspend fun getCertificate(serverUrl: String): Either<CertificateStorageError, Pair<String, String>> = either {
        catch({
            val certDataFilePath = getCertDataFilePath(serverUrl)
            ensure(fileSystem.exists(certDataFilePath)) {
                CertificateStorageError.NotFound("Certificate file not found for server: $serverUrl")
            }

            val storedJson = fileSystem.source(certDataFilePath).buffered().use { it.readString() }
            val storedData = json.decodeFromString<StoredCertificateData>(storedJson)

            logger.info("Successfully retrieved certificate for $serverUrl")
            Pair(storedData.pem, storedData.fingerprint)
        }) { e ->
            logger.error("Failed to get certificate for $serverUrl", e)
            raise(mapExceptionToError(e, "Failed to get certificate"))
        }
    }

    override suspend fun removeCertificate(serverUrl: String): Either<CertificateStorageError, Unit> = either {
        catch({
            val serverDir = getServerDirectory(serverUrl)
            if (fileSystem.exists(serverDir)) {
                // Delete file first
                val certDataFilePath = getCertDataFilePath(serverUrl)
                if (fileSystem.exists(certDataFilePath)) {
                    fileSystem.delete(certDataFilePath)
                }
                // Delete directory
                fileSystem.delete(serverDir)
                logger.info("Removed certificate directory for $serverUrl")
            }
        }) { e ->
            logger.error("Failed to remove certificate for $serverUrl", e)
            raise(mapExceptionToError(e, "Failed to remove certificate directory"))
        }
    }

    override suspend fun hasCertificate(serverUrl: String): Either<CertificateStorageError, Boolean> = either {
        catch({
            fileSystem.exists(getCertDataFilePath(serverUrl))
        }) { e ->
            logger.error("Failed to check certificate existence for $serverUrl", e)
            raise(mapExceptionToError(e, "Failed to check certificate existence"))
        }
    }

    // --- Private Helper Functions ---

    private fun sanitizeUrlForPath(url: String): String {
        // Replaces common URL characters with underscores to create a safe directory name.
        // Ensures paths are valid across different OS.
        return url.replace(Regex("[^a-zA-Z0-9.-]"), "_")
    }

    private fun getServerDirectory(serverUrl: String): Path {
        return Path(storageDirPath, sanitizeUrlForPath(serverUrl))
    }

    private fun getCertDataFilePath(serverUrl: String): Path {
        return Path(getServerDirectory(serverUrl), "certificate.json")
    }

    // Maps general Exceptions to CertificateStorageError
    private fun mapExceptionToError(e: Throwable, context: String? = null): CertificateStorageError {
        val message = context?.let { "$it: ${e.message}" } ?: e.message ?: "An unknown error occurred"
        return when (e) {
            is kotlinx.io.IOException -> CertificateStorageError.IOError(message, e)
            is SerializationException -> CertificateStorageError.InvalidFormat(message, e)
            else -> CertificateStorageError.Unknown(message, e)
        }
    }
}
