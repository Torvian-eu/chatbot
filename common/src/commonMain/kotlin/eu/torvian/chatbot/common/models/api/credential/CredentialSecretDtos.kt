package eu.torvian.chatbot.common.models.api.credential

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Operator-facing view of one encrypted credential secret.
 *
 * The value is not part of this type in any form, not even masked: the server stores ciphertext it
 * cannot decrypt, so there is nothing to show and nothing to edit except by replacement.
 *
 * @property id Server-side row identifier, used by delete notifications and updates.
 * @property workerId Worker this secret is bound to; immutable after creation.
 * @property reference Name a profile uses to reference this secret.
 * @property algorithm Cipher identifier of the outer key wrap, for diagnostics.
 * @property workerCertificateFingerprint Fingerprint of the worker certificate whose public key can
 *   unwrap this secret; a mismatch after a certificate rotation is a re-entry, not a bug.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last modification timestamp.
 */
@Serializable
data class CredentialSecretMetadataDto(
    val id: Long,
    val workerId: Long,
    val reference: String,
    val algorithm: String,
    val workerCertificateFingerprint: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Worker-facing credential secret, carried as ciphertext only.
 *
 * The secret is wrapped in two layers and neither field holds the value:
 *
 * - [encryptedDek] is the **data key** — random bytes the server generates once per secret — wrapped
 *   with the worker certificate public key.
 * - [encryptedSecret] is the value encrypted with that data key.
 *
 * The worker unwraps [encryptedDek] with its identity private key and decrypts [encryptedSecret] only
 * inside `attach`, so the value exists in worker memory only for the duration of material
 * construction. Two layers are required: a bare RSA wrap of the value carries far less than the
 * configured `maxSecretBytes`.
 *
 * @property id Server-side row identifier, used by delete notifications.
 * @property workerId Worker this secret is bound to; immutable after creation.
 * @property reference Name a profile uses to reference this secret.
 * @property encryptedSecret Ciphertext of the value, Base64-encoded.
 * @property encryptedDek Data key wrapped with the worker certificate public key, Base64-encoded.
 *   Not the value and not a key usable without the worker private key.
 * @property algorithm Cipher identifier of the outer key wrap.
 * @property workerCertificateFingerprint Fingerprint of the certificate whose public key produced
 *   [encryptedDek].
 * @property updatedAt Last modification timestamp.
 */
@Serializable
data class WorkerCredentialSecretDto(
    val id: Long,
    val workerId: Long,
    val reference: String,
    val encryptedSecret: String,
    val encryptedDek: String,
    val algorithm: String,
    val workerCertificateFingerprint: String,
    val updatedAt: Instant,
)

/**
 * Body the app sends to store a new secret value.
 *
 * The plaintext appears here only: the server encrypts it immediately and never returns it.
 *
 * @property workerId Worker the secret is bound to; immutable after creation.
 * @property reference Name a profile will use to reference this secret.
 * @property value Secret value in plaintext. Never echoed in a response or a log line.
 */
@Serializable
data class CreateCredentialSecretRequest(
    val workerId: Long,
    val reference: String,
    val value: String,
)

/**
 * Body the app sends to replace a stored secret value.
 *
 * Replacement is the only edit: the server cannot decrypt the stored value, so it cannot diff it, and
 * the operator cannot read it back.
 *
 * @property reference Name a profile uses to reference this secret.
 * @property value New secret value in plaintext. Never echoed in a response or a log line.
 */
@Serializable
data class UpdateCredentialSecretRequest(
    val reference: String,
    val value: String,
)
