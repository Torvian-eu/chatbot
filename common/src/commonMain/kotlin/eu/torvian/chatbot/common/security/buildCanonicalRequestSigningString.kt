package eu.torvian.chatbot.common.security

/**
 * Builds the canonical string that request-signing and request-verification code must treat as the exact signed text.
 *
 * The delimiter and field order are part of the authorization protocol contract. Both signing and verification must
 * continue to use the exact same format to preserve signature compatibility.
 *
 * @param timestamp Epoch-millisecond timestamp included in the signature input.
 * @param nonce Unique replay-protection token included in the signature input.
 * @param signerId Stable identifier for the signing device.
 * @param payload Exact serialized request body string.
 * @return Canonical string that should be signed or verified.
 */
fun buildCanonicalRequestSigningString(
    timestamp: Long,
    nonce: String,
    signerId: String,
    payload: String
): String = "$timestamp|$nonce|$signerId|$payload"

/**
 * Builds the canonical signing string from an existing [SignedRequest] value object.
 *
 * @param request Signed request whose fields should be assembled into canonical signing input.
 * @return Canonical string that should be signed or verified.
 */
fun buildCanonicalRequestSigningString(request: SignedRequest): String = buildCanonicalRequestSigningString(
    timestamp = request.timestamp,
    nonce = request.nonce,
    signerId = request.signerId,
    payload = request.payload
)