package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Persisted signature row binding a credential profile payload to the device that signed it.
 *
 * @property profileId Profile the signature belongs to.
 * @property userDeviceId Signer device identifier.
 * @property signature Base64-encoded signature over [payloadJson].
 * @property timestamp Signature creation time included in the signed input, in epoch milliseconds.
 * @property nonce Replay-protection token included in the signed input.
 * @property payloadJson Exact raw JSON payload that was signed.
 * @property createdAt Creation timestamp.
 * @property updatedAt Last update timestamp.
 */
data class WorkerCredentialProfileSignatureEntity(
    val profileId: Long,
    val userDeviceId: Long,
    val signature: String,
    val timestamp: Long,
    val nonce: String,
    val payloadJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
