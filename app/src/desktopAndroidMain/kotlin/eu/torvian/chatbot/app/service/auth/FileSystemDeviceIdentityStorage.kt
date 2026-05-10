package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * FileSystem-based implementation of [DeviceIdentityStorage].
 *
 * This implementation stores the device ID in a simple text file in the app's storage directory.
 * The device ID is stored in a dedicated subdirectory to keep it separate from user account data.
 *
 * @param storageDirectoryPath The absolute path to the directory for storing the device ID file.
 * @param fileSystem The KMP FileSystem to use. Defaults to the system's default file system.
 */
class FileSystemDeviceIdentityStorage(
    storageDirectoryPath: String,
    private val fileSystem: FileSystem = SystemFileSystem
) : DeviceIdentityStorage {

    private val logger = kmpLogger<FileSystemDeviceIdentityStorage>()

    private val storageDirPath = Path(storageDirectoryPath)
    private val deviceIdFilePath: Path = Path(storageDirPath, "device_id.txt")

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
}
