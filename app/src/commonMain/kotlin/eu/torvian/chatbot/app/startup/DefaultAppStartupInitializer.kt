package eu.torvian.chatbot.app.startup

import arrow.core.getOrElse
import eu.torvian.chatbot.app.service.auth.DeviceIdentityService
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Default implementation of [AppStartupInitializer] that eagerly ensures the device
 * identity and signing key pair exist and logs safe public identity information.
 *
 * This initializer is idempotent within a single process lifetime: once [initialize]
 * completes successfully, subsequent calls are no-ops.
 *
 * @property deviceIdentityService Service used to get or create device identity material.
 */
@OptIn(ExperimentalEncodingApi::class)
class DefaultAppStartupInitializer(
    private val deviceIdentityService: DeviceIdentityService
) : AppStartupInitializer {

    private val logger = kmpLogger<DefaultAppStartupInitializer>()

    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    override suspend fun initialize() {
        mutex.withLock {
            if (initialized) {
                logger.debug("Startup initialization already completed, skipping")
                return
            }

            val deviceId = deviceIdentityService.getOrCreateDeviceId().getOrElse {
                logger.error("Failed to initialize device identity: ${it.message}")
                return
            }

            val keyPair = deviceIdentityService.getOrCreateSigningKeyPair().getOrElse {
                logger.error("Failed to initialize signing key pair: ${it.message}")
                return
            }

            val publicKeyBase64 = Base64.encode(keyPair.publicKey)

            logger.info("Startup identity initialization completed")
            logger.info("Device ID: ${deviceId.take(8)}...") // Log a truncated version of the device ID for privacy
            logger.info("Signing public key: $publicKeyBase64")

            initialized = true
        }
    }
}
