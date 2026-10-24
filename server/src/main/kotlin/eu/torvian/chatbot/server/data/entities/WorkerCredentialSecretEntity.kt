package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Persisted credential secret row, carrying ciphertext only.
 *
 * @property id Row identifier.
 * @property workerId Worker that can decrypt the value; immutable after insert.
 * @property reference Name a profile uses to reference the secret, unique per worker.
 * @property encryptedSecret Ciphertext of the value, Base64-encoded.
 * @property encryptedDek Data key wrapped with the worker certificate public key, Base64-encoded.
 * @property algorithm Cipher identifier of the outer key wrap.
 * @property workerCertificateFingerprint Fingerprint of the wrapping certificate.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last update timestamp.
 */
data class WorkerCredentialSecretEntity(
    val id: Long,
    val workerId: Long,
    val reference: String,
    val encryptedSecret: String,
    val encryptedDek: String,
    val algorithm: String,
    val workerCertificateFingerprint: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Fields needed to insert a credential secret.
 *
 * @property workerId Worker that can decrypt the value.
 * @property reference Name a profile will use to reference the secret.
 * @property encryptedSecret Ciphertext of the value, Base64-encoded.
 * @property encryptedDek Data key wrapped with the worker certificate public key, Base64-encoded.
 * @property algorithm Cipher identifier of the outer key wrap.
 * @property workerCertificateFingerprint Fingerprint of the wrapping certificate.
 */
data class CreateWorkerCredentialSecretEntity(
    val workerId: Long,
    val reference: String,
    val encryptedSecret: String,
    val encryptedDek: String,
    val algorithm: String,
    val workerCertificateFingerprint: String,
)

/**
 * Replacement values for an existing credential secret.
 *
 * The worker binding is absent on purpose: the server cannot re-encrypt, so a secret can never move
 * between workers and the column is fixed at insert.
 *
 * @property reference Name a profile uses to reference the secret.
 * @property encryptedSecret Ciphertext of the new value, Base64-encoded.
 * @property encryptedDek Data key wrapped with the worker certificate public key, Base64-encoded.
 * @property algorithm Cipher identifier of the outer key wrap.
 * @property workerCertificateFingerprint Fingerprint of the wrapping certificate.
 */
data class UpdateWorkerCredentialSecretEntity(
    val reference: String,
    val encryptedSecret: String,
    val encryptedDek: String,
    val algorithm: String,
    val workerCertificateFingerprint: String,
)
