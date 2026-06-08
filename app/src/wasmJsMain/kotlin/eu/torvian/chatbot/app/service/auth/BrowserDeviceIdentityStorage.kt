package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.security.AsymmetricKeyPair
import kotlinx.browser.localStorage
import org.w3c.dom.Storage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Browser-based implementation of [DeviceIdentityStorage].
 *
 * This implementation stores the device ID and signing key pair in the browser's localStorage.
 * The data is stored under specific keys to keep it separate from other app data.
 *
 * @param storageNamespace A namespace prefix used to scope localStorage keys.
 * @param storage The Web Storage instance to use. Defaults to [localStorage].
 */
@OptIn(ExperimentalEncodingApi::class)
class BrowserDeviceIdentityStorage(
    private val storageNamespace: String,
    private val storage: Storage = localStorage
) : DeviceIdentityStorage {

    private val logger = kmpLogger<BrowserDeviceIdentityStorage>()

    private val deviceIdKey: String = "$storageNamespace/device_id"
    private val signingKeyPairKey: String = "$storageNamespace/signing_keypair"

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

    override suspend fun loadSigningKeyPair(): Either<DeviceIdentityStorageError, AsymmetricKeyPair?> = either {
        catch({
            val keyPairData = storage.getItem(signingKeyPairKey)
            if (keyPairData.isNullOrEmpty()) {
                return@either null
            }

            // Parse the stored format: "publicKeyBase64\nprivateKeyBase64"
            val lines = keyPairData.lines()
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
            // Store in format: "publicKeyBase64\nprivateKeyBase64"
            val publicKeyBase64 = Base64.encode(keyPair.publicKey)
            val privateKeyBase64 = Base64.encode(keyPair.privateKey)
            val content = "$publicKeyBase64\n$privateKeyBase64"

            storage.setItem(signingKeyPairKey, content)
            logger.info("Saved signing key pair to localStorage")
        }) { e: Exception ->
            raise(DeviceIdentityStorageError.IOError("Failed to save signing key pair: ${e.message}"))
        }
    }
}
