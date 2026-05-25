package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Default implementation of [DeviceIdentityService].
 *
 * This implementation:
 * - Reads the stored device ID from the provided storage once per process lifetime
 * - Caches the result in memory to avoid repeated disk I/O
 * - If no ID is stored, generates a new UUID and persists it
 * - Returns the device ID for use in login requests
 *
 * @property storage The storage for device identity persistence
 */
@OptIn(ExperimentalUuidApi::class)
class DefaultDeviceIdentityService(
    private val storage: DeviceIdentityStorage
) : DeviceIdentityService {

    private val logger = kmpLogger<DefaultDeviceIdentityService>()

    private val mutex = Mutex()

    @Volatile
    private var cachedDeviceId: String? = null

    override suspend fun getOrCreateDeviceId(): Either<DeviceIdentityError, String> = either {
        // Fast path: return cached ID without synchronization
        cachedDeviceId?.let { return@either it }

        mutex.withLock {
            // Double-check after acquiring lock
            cachedDeviceId?.let { return@either it }

            // First, try to load an existing device ID
            val existingId = storage.loadDeviceId().mapLeft { error ->
                DeviceIdentityError.ReadFailure(error.message)
            }.bind()

            if (existingId != null) {
                logger.info("Retrieved existing device ID from storage")
                cachedDeviceId = existingId
                return@either existingId
            }

            // No existing ID - generate a new one using Kotlin's UUID
            val newDeviceId = Uuid.random().toString()
            logger.info("Generated new device ID: $newDeviceId")

            // Save the new device ID
            storage.saveDeviceId(newDeviceId).mapLeft { error ->
                DeviceIdentityError.PersistenceFailure(error.message)
            }.bind()

            cachedDeviceId = newDeviceId
            newDeviceId
        }
    }
}
