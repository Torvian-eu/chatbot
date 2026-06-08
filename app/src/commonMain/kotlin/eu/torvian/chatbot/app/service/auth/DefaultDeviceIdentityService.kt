package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.security.AsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.AsymmetricKeyPair
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Default implementation of [DeviceIdentityService].
 *
 * This implementation:
 * - Reads the stored device ID and signing key pair from the provided storage once per process lifetime
 * - Caches the results in memory to avoid repeated disk I/O
 * - If no ID or key pair is stored, generates new ones and persists them
 * - Returns the device ID and key pair for use in authentication and signing requests
 *
 * @property storage The storage for device identity persistence
 * @property cryptoProvider The crypto provider for key pair generation
 */
@OptIn(ExperimentalUuidApi::class)
class DefaultDeviceIdentityService(
    private val storage: DeviceIdentityStorage,
    private val cryptoProvider: AsymmetricCryptoProvider
) : DeviceIdentityService {

    private val logger = kmpLogger<DefaultDeviceIdentityService>()

    private val mutex = Mutex()

    @Volatile
    private var cachedDeviceId: String? = null

    @Volatile
    private var cachedSigningKeyPair: AsymmetricKeyPair? = null

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

    override suspend fun getOrCreateSigningKeyPair(): Either<DeviceIdentityError, AsymmetricKeyPair> = either {
        // Fast path: return cached key pair without synchronization
        cachedSigningKeyPair?.let { return@either it }

        mutex.withLock {
            // Double-check after acquiring lock
            cachedSigningKeyPair?.let { return@either it }

            // First, try to load an existing key pair
            val existingKeyPair = storage.loadSigningKeyPair().mapLeft { error ->
                DeviceIdentityError.ReadFailure(error.message)
            }.bind()

            if (existingKeyPair != null) {
                logger.info("Retrieved existing signing key pair from storage")
                cachedSigningKeyPair = existingKeyPair
                return@either existingKeyPair
            }

            // No existing key pair - generate a new one
            val newKeyPair = cryptoProvider.generateKeyPair().mapLeft { error ->
                DeviceIdentityError.KeyGenerationFailure(error.message)
            }.bind()

            logger.info("Generated new signing key pair")

            // Save the new key pair
            storage.saveSigningKeyPair(newKeyPair).mapLeft { error ->
                DeviceIdentityError.PersistenceFailure(error.message)
            }.bind()

            cachedSigningKeyPair = newKeyPair
            newKeyPair
        }
    }
}
