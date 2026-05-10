package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.browser.localStorage
import org.w3c.dom.Storage

/**
 * Browser-based implementation of [DeviceIdentityStorage].
 *
 * This implementation stores the device ID in the browser's localStorage.
 * The device ID is stored under a specific key to keep it separate from other app data.
 *
 * @param storageNamespace A namespace prefix used to scope localStorage keys.
 * @param storage The Web Storage instance to use. Defaults to [localStorage].
 */
class BrowserDeviceIdentityStorage(
    private val storageNamespace: String,
    private val storage: Storage = localStorage
) : DeviceIdentityStorage {

    private val logger = kmpLogger<BrowserDeviceIdentityStorage>()

    private val deviceIdKey: String = "$storageNamespace/device_id"

    override suspend fun loadDeviceId(): Either<DeviceIdentityStorageError, String?> = either {
        catch({
            val deviceId = storage.getItem(deviceIdKey)
            if (deviceId.isNullOrEmpty()) {
                null
            } else {
                deviceId
            }
        }) { e: Exception ->
            raise(DeviceIdentityStorageError.IOError("Failed to load device ID: ${e.message}"))
        }
    }

    override suspend fun saveDeviceId(deviceId: String): Either<DeviceIdentityStorageError, Unit> = either {
        catch({
            storage.setItem(deviceIdKey, deviceId)
            logger.info("Saved device ID to localStorage")
        }) { e: Exception ->
            raise(DeviceIdentityStorageError.IOError("Failed to save device ID: ${e.message}"))
        }
    }
}
