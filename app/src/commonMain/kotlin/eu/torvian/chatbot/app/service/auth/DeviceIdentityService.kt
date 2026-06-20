package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import eu.torvian.chatbot.common.security.AsymmetricKeyPair

/**
 * Service for managing persistent device identity.
 *
 * This service provides a stable device ID and signing key pair that persist across app restarts and logouts.
 * The device ID and key pair are app-install scoped, not account scoped.
 */
interface DeviceIdentityService {

    /**
     * Gets the existing device ID or creates a new one if not present.
     * The device ID persists across app restarts and is stable for the app installation.
     *
     * @return Either a [DeviceIdentityError] on failure or the device ID string on success
     */
    suspend fun getOrCreateDeviceId(): Either<DeviceIdentityError, String>

    /**
     * Gets the existing signing key pair or creates a new one if not present.
     * The key pair persists across app restarts and is used for signing requests.
     *
     * @return Either a [DeviceIdentityError] on failure or the [AsymmetricKeyPair] on success
     */
    suspend fun getOrCreateSigningKeyPair(): Either<DeviceIdentityError, AsymmetricKeyPair>
}

/**
 * Errors that can occur during device identity operations.
 */
sealed interface DeviceIdentityError {

    /**
     * A description of the error.
     */
    val message: String

    /**
     * Represents a failure to persist the device ID.
     *
     * @property message A description of the error
     */
    data class PersistenceFailure(override val message: String) : DeviceIdentityError

    /**
     * Represents a failure to read the stored device ID.
     *
     * @property message A description of the error
     */
    data class ReadFailure(override val message: String) : DeviceIdentityError

    /**
     * Represents a failure during key pair generation.
     *
     * @property message A description of the error
     */
    data class KeyGenerationFailure(override val message: String) : DeviceIdentityError
}
