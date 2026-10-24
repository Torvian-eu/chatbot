package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import kotlin.time.Clock

/**
 * Server-side storage for worker-bound credential secrets.
 *
 * Only ciphertext is stored: the server holds neither the value nor the data key, so it cannot decrypt
 * a row or re-encrypt it for another worker. `workerId` is therefore immutable after insert.
 *
 * @property workerId Worker that can decrypt this secret.
 * @property reference Name a profile uses to reference the secret, unique per worker.
 * @property encryptedSecret Ciphertext of the value, Base64-encoded.
 * @property encryptedDek Data key wrapped with the worker certificate public key, Base64-encoded.
 * @property algorithm Cipher identifier of the outer key wrap.
 * @property workerCertificateFingerprint Fingerprint of the certificate whose public key wrapped the
 *   data key, so a rotation is distinguishable from a corrupt payload.
 * @property createdAt Creation timestamp in epoch milliseconds.
 * @property updatedAt Last update timestamp in epoch milliseconds.
 */
object WorkerCredentialSecretTable : LongIdTable("worker_credential_secrets") {
    val workerId = reference("worker_id", WorkersTable, onDelete = ReferenceOption.CASCADE)
    val reference = varchar("reference", 255)
    val encryptedSecret = text("encrypted_secret")
    val encryptedDek = text("encrypted_dek")
    val algorithm = varchar("algorithm", 128)
    val workerCertificateFingerprint = varchar("worker_certificate_fingerprint", 128)
    val createdAt = long("created_at").clientDefault { Clock.System.now().toEpochMilliseconds() }
    val updatedAt = long("updated_at").clientDefault { Clock.System.now().toEpochMilliseconds() }
}
