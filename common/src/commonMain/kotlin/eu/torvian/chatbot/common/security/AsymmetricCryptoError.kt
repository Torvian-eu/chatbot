package eu.torvian.chatbot.common.security

/**
 * Sealed class representing errors specific to asymmetric cryptographic operations.
 *
 * These errors are for signing and key-pair operations, separate from symmetric encryption
 * errors defined in [CryptoError]. They follow the Arrow typed-error style for logical errors.
 */
sealed class AsymmetricCryptoError {
    /**
     * A comprehensive, technical message describing the error, suitable for logging and debugging.
     * This message often includes details from the underlying [cause] if available.
     */
    abstract val message: String

    /**
     * The underlying [Throwable] that caused this error, if applicable.
     */
    abstract val cause: Throwable?

    /**
     * Error during the signing operation.
     */
    data class SignatureGenerationFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : AsymmetricCryptoError()

    /**
     * Error during the signature verification operation.
     *
     * Note: This represents an operational/technical failure, not simply a signature mismatch.
     * A signature mismatch (valid signature but wrong key) should be indicated by returning
     * `Right(false)` from the verification function, not by this error.
     */
    data class SignatureVerificationFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : AsymmetricCryptoError()

    /**
     * Error during asymmetric key pair generation.
     */
    data class KeyGenerationFailed(
        override val message: String,
        override val cause: Throwable? = null
    ) : AsymmetricCryptoError()

}
