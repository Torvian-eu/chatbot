package eu.torvian.chatbot.worker.service.security

import eu.torvian.chatbot.common.security.AsymmetricCryptoError

/**
 * Logical failures that can occur while validating a signed worker request.
 *
 * The variants intentionally avoid exposing cryptographic implementation details to callers;
 * every failed trust decision is reduced to a small, stable authorization error model.
 */
sealed class VerificationError {
    /**
     * Indicates that no local trust-store entry exists for the request signer.
     *
     * @property signerId Signer identifier that was present in the rejected request.
     */
    data class UnknownSigner(val signerId: String) : VerificationError()

    /**
     * Indicates that the request signature does not validate for its reconstructed signed data.
     *
     * @property cause Optional cryptographic error that prevented signature verification.
     */
    data class InvalidSignature(val cause: AsymmetricCryptoError? = null) : VerificationError()

    /**
     * Indicates that a request timestamp is outside the accepted transient-command window.
     *
     * @property timestamp Request creation time in epoch milliseconds.
     * @property ageSeconds Signed age relative to the worker clock in seconds.
     */
    data class Expired(val timestamp: Long, val ageSeconds: Long) : VerificationError()
}