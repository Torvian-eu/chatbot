package eu.torvian.chatbot.app.service.security

import eu.torvian.chatbot.common.security.AsymmetricCryptoError

/**
 * Errors that can occur while preparing detached signatures for raw request payloads.
 *
 * These errors keep protocol-level signing generic so callers are not coupled to MCP-specific concepts.
 */
sealed interface RequestSigningError {

    /**
     * Human-readable explanation of the failure.
     */
    val message: String

    /**
     * Underlying platform or implementation cause when one exists.
     */
    val cause: Throwable?

    /**
     * Indicates that the request payload could not be serialized to a canonical string form for signing.
     *
     * @property message Human-readable explanation of the serialization failure.
     * @property cause Underlying platform or implementation failure, if available.
     */
    data class SerializationFailure(
        override val message: String,
        override val cause: Throwable? = null
    ) : RequestSigningError

    /**
     * Indicates that stable signer identity material could not be loaded or created.
     *
     * @property message Human-readable explanation of the missing identity state.
     * @property cause Underlying platform or storage failure, if available.
     */
    data class IdentityMissing(
        override val message: String,
        override val cause: Throwable? = null
    ) : RequestSigningError

    /**
     * Indicates that the cryptographic provider could not produce a signature for the canonical request string.
     *
     * @property message Human-readable explanation of the signing failure.
     * @property cause Underlying platform or implementation failure, if available.
     * @property cryptoError Structured cryptographic error returned by the provider.
     */
    data class CryptoFailure(
        override val message: String,
        override val cause: Throwable? = null,
        val cryptoError: AsymmetricCryptoError
    ) : RequestSigningError
}