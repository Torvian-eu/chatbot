package eu.torvian.chatbot.app.service.security

import arrow.core.Either

/**
 * Interface for securely storing and retrieving server certificates.
 *
 * Implementations are expected to persist a PEM encoded certificate and a
 * fingerprint for a specific server URL. All operations return an [Either]
 * with a [CertificateStorageError] on the left to allow precise error handling
 * without throwing exceptions across suspend boundaries.
 */
interface CertificateStorage {
    /**
     * Stores a server certificate for a given server URL.
     *
     * @param serverUrl The canonical server identifier (e.g. base URI) used as the storage key.
     * @param certificatePem The certificate encoded in PEM format.
     * @param fingerprint The SHA-256 fingerprint string for quick comparisons.
     * @return Either a [CertificateStorageError] on failure or Unit on success.
     */
    suspend fun storeCertificate(
        serverUrl: String,
        certificatePem: String,
        fingerprint: String
    ): Either<CertificateStorageError, Unit>

    /**
     * Retrieves the stored certificate (PEM and fingerprint) for a given server URL.
     *
     * @param serverUrl The canonical server identifier used as the storage key.
     * @return Either a [CertificateStorageError] (including [CertificateStorageError.NotFound])
     * or a [Pair] of (PEM, fingerprint) on the right.
     */
    suspend fun getCertificate(serverUrl: String): Either<CertificateStorageError, Pair<String, String>>

    /**
     * Removes the stored certificate for a given server URL.
     *
     * Implementations should remove any files or metadata associated with the entry.
     *
     * @param serverUrl The canonical server identifier used as the storage key.
     * @return Either a [CertificateStorageError] on failure or Unit on success.
     */
    suspend fun removeCertificate(serverUrl: String): Either<CertificateStorageError, Unit>

    /**
     * Checks if a certificate is stored for a given server URL.
     *
     * This is a convenience function that may avoid a full decryption round-trip.
     *
     * @param serverUrl The canonical server identifier used as the storage key.
     * @return Either a [CertificateStorageError] on I/O failure or a Boolean indicating existence.
     */
    suspend fun hasCertificate(serverUrl: String): Either<CertificateStorageError, Boolean>
}
