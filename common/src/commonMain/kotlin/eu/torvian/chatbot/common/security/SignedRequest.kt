package eu.torvian.chatbot.common.security

import kotlinx.serialization.Serializable

/**
 * Detached representation of a signed request body plus the metadata needed to transmit or persist its signature.
 *
 * This model does not imply that the payload must be wrapped inside a new JSON object for transport. Callers can
 * send [payload] as the normal HTTP body and propagate the remaining fields through protocol-level metadata such as
 * headers.
 *
 * @property payload Exact serialized request body string that was signed.
 * @property signature Base64-encoded digital signature computed from the canonical signing string.
 * @property signerId Stable app/device identifier that produced the signature.
 * @property timestamp Epoch-millisecond creation time included in the signature input.
 * @property nonce Unique replay-protection token included in the signature input.
 */
@Serializable
data class SignedRequest(
    val payload: String,
    val signature: String,
    val signerId: String,
    val timestamp: Long,
    val nonce: String
)