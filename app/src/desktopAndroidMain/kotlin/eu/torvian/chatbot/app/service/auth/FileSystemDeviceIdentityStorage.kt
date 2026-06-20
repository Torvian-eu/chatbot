package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.security.AsymmetricKeyPair
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * FileSystem-based implementation of [DeviceIdentityStorage].
 *
 * This implementation stores the device ID and signing key pair in files in the app's storage directory.
 * The data is stored in a dedicated subdirectory to keep it separate from user account data.
 *
 * @param storageDirectoryPath The absolute path to the directory for storing identity files.
 * @param fileSystem The KMP FileSystem to use. Defaults to the system's default file system.
 */
@OptIn(ExperimentalEncodingApi::class)
class FileSystemDeviceIdentityStorage(
    storageDirectoryPath: String,
    private val fileSystem: FileSystem = SystemFileSystem
) : DeviceIdentityStorage {

    private val logger = kmpLogger<FileSystemDeviceIdentityStorage>()

    private val storageDirPath = Path(storageDirectoryPath)
    private val deviceIdFilePath: Path = Path(storageDirPath, "device_id.txt")
    private val signingKeyPairFilePath: Path = Path(storageDirPath, "signing_keypair.txt")

    override suspend fun loadDeviceId(): Either<DeviceIdentityStorageError, String?> = either {
        catch({
            // Ensure the directory exists
            if (!fileSystem.exists(storageDirPath)) {
                fileSystem.createDirectories(storageDirPath)
            }

            if (!fileSystem.exists(deviceIdFilePath)) {
                return@either null
            }

            val content = fileSystem.source(deviceIdFilePath).buffered().use { it.readString() }.trim()
            content.ifEmpty {
                null
            }
        }) { e: Exception ->
            raise(DeviceIdentityStorageError.IOError("Failed to load device ID: ${e.message}"))
        }
    }

    override suspend fun saveDeviceId(deviceId: String): Either<DeviceIdentityStorageError, Unit> = either {
        catch({
            // Ensure the directory exists
            if (!fileSystem.exists(storageDirPath)) {
                fileSystem.createDirectories(storageDirPath)
            }

            fileSystem.sink(deviceIdFilePath).buffered().use { it.writeString(deviceId) }
            logger.info("Saved device ID to storage")
        }) { e: Exception ->
            raise(DeviceIdentityStorageError.IOError("Failed to save device ID: ${e.message}"))
        }
    }

    override suspend fun loadSigningKeyPair(): Either<DeviceIdentityStorageError, AsymmetricKeyPair?> = either {
        catch({
            // Ensure the directory exists
            if (!fileSystem.exists(storageDirPath)) {
                fileSystem.createDirectories(storageDirPath)
            }

            if (!fileSystem.exists(signingKeyPairFilePath)) {
                return@either null
            }

            val content = fileSystem.source(signingKeyPairFilePath).buffered().use { it.readString() }.trim()
            if (content.isEmpty()) {
                return@either null
            }

            // Parse the stored format: "publicKeyBase64\nprivateKeyBase64"
            val lines = content.lines()
            if (lines.size != 2) {
                raise(DeviceIdentityStorageError.ParseError("Invalid key pair format: expected 2 lines, got ${lines.size}"))
            }

            val publicKey = Base64.decode(lines[0])
            val privateKey = Base64.decode(lines[1])

            AsymmetricKeyPair(publicKey = publicKey, privateKey = privateKey)
        }) { e: Exception ->
            raise(DeviceIdentityStorageError.IOError("Failed to load signing key pair: ${e.message}"))
        }
    }

    override suspend fun saveSigningKeyPair(keyPair: AsymmetricKeyPair): Either<DeviceIdentityStorageError, Unit> = either {
        catch({
            // Ensure the directory exists
            if (!fileSystem.exists(storageDirPath)) {
                fileSystem.createDirectories(storageDirPath)
            }

            // Store in format: "publicKeyBase64\nprivateKeyBase64"
            val publicKeyBase64 = Base64.encode(keyPair.publicKey)
            val privateKeyBase64 = Base64.encode(keyPair.privateKey)
            val content = "$publicKeyBase64\n$privateKeyBase64"

            fileSystem.sink(signingKeyPairFilePath).buffered().use { it.writeString(content) }
            logger.info("Saved signing key pair to storage")
        }) { e: Exception ->
            raise(DeviceIdentityStorageError.IOError("Failed to save signing key pair: ${e.message}"))
        }
    }
}
