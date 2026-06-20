package eu.torvian.chatbot.common.security

/**
 * Shared HTTP header names used to transport detached [SignedRequest] metadata.
 *
 * The signed payload itself remains the normal HTTP request body. These headers carry only the detached signature
 * metadata that must stay byte-for-byte aligned with that body for downstream verification and persistence.
 */
object SignedRequestHttpHeaders {
    /**
     * Header containing the Base64-encoded detached signature.
     */
    const val SIGNATURE: String = "X-Torvian-Signature"

    /**
     * Header containing the stable device or signer identifier.
     */
    const val SIGNER_ID: String = "X-Torvian-Signer-Id"

    /**
     * Header containing the epoch-millisecond timestamp embedded in the signed canonical string.
     */
    const val TIMESTAMP: String = "X-Torvian-Timestamp"

    /**
     * Header containing the replay-protection nonce embedded in the signed canonical string.
     */
    const val NONCE: String = "X-Torvian-Nonce"

    /**
     * Ordered list of all required detached-signature header names.
     *
     * Keeping the order stable makes diagnostics and tests deterministic.
     */
    val all: List<String> = listOf(SIGNATURE, SIGNER_ID, TIMESTAMP, NONCE)
}

/**
 * Converts detached signature metadata into HTTP headers while leaving [SignedRequest.payload] as the normal body.
 *
 * @receiver Signed request whose detached metadata should be exposed as HTTP headers.
 * @return Header map that callers can attach to outgoing HTTP requests.
 */
fun SignedRequest.toDetachedSignatureHeaders(): Map<String, String> = linkedMapOf(
    SignedRequestHttpHeaders.SIGNATURE to signature,
    SignedRequestHttpHeaders.SIGNER_ID to signerId,
    SignedRequestHttpHeaders.TIMESTAMP to timestamp.toString(),
    SignedRequestHttpHeaders.NONCE to nonce
)