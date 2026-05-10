package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Default implementation of [DeviceIdentityService].
 *
 * This implementation:
 * - Reads the stored device ID from the provided storage
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

    override suspend fun getOrCreateDeviceId(): Either<DeviceIdentityError, String> = either {
        // First, try to load an existing device ID
        val existingId = storage.loadDeviceId().mapLeft { error ->
            DeviceIdentityError.ReadFailure(error.message)
        }.bind()

        if (existingId != null) {
            logger.info("Retrieved existing device ID from storage")
            return@either existingId
        }

        // No existing ID - generate a new one using Kotlin's UUID
        val newDeviceId = Uuid.random().toString()
        logger.info("Generated new device ID: $newDeviceId")

        // Save the new device ID
        storage.saveDeviceId(newDeviceId).mapLeft { error ->
            DeviceIdentityError.PersistenceFailure(error.message)
        }.bind()

        newDeviceId
    }
}
