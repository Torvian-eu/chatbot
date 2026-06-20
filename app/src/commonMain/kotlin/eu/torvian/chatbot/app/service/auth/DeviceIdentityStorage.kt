package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import eu.torvian.chatbot.common.security.AsymmetricKeyPair

/**
 * Interface for storing and retrieving device identity.
 *
 * This is a simple key-value storage abstraction for the device ID and signing key pair.
 */
interface DeviceIdentityStorage {

    /**
     * Retrieves a stored device ID.
     *
     * @return Either a storage error or the stored device ID (null if not set)
     */
    suspend fun loadDeviceId(): Either<DeviceIdentityStorageError, String?>

    /**
     * Stores the device ID.
     *
     * @param deviceId The device ID to store
     * @return Either a storage error or Unit on success
     */
    suspend fun saveDeviceId(deviceId: String): Either<DeviceIdentityStorageError, Unit>

    /**
     * Retrieves a stored signing key pair.
     *
     * @return Either a storage error or the stored key pair (null if not set)
     */
    suspend fun loadSigningKeyPair(): Either<DeviceIdentityStorageError, AsymmetricKeyPair?>

    /**
     * Stores the signing key pair.
     *
     * @param keyPair The key pair to store
     * @return Either a storage error or Unit on success
     */
    suspend fun saveSigningKeyPair(keyPair: AsymmetricKeyPair): Either<DeviceIdentityStorageError, Unit>
}

/**
 * Errors that can occur during device identity storage operations.
 */
sealed interface DeviceIdentityStorageError {

    /** A description of the error */
    val message: String

    /**
     * Represents an I/O error during storage operations.
     *
     * @property message A description of the error
     */
    data class IOError(override val message: String) : DeviceIdentityStorageError

    /**
     * Represents a failure to parse stored data.
     *
     * @property message A description of the error
     */
    data class ParseError(override val message: String) : DeviceIdentityStorageError
}
