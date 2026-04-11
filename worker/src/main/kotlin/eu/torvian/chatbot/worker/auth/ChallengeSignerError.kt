package eu.torvian.chatbot.worker.auth

/**
 * Logical errors while loading or using the worker private key for challenge signing.
 */
sealed interface ChallengeSignerError {
    /**
     * Indicates that the worker private key could not be parsed from the configured PEM file.
     *
     * @property reason Human-readable parse failure reason.
     */
    data class PrivateKeyParseFailed(val reason: String) : ChallengeSignerError

    /**
     * Indicates that the parsed private key algorithm is not supported by this signer.
     *
     * @property algorithm Parsed key algorithm name (for example, RSA, EC, Ed25519).
     */
    data class UnsupportedKeyAlgorithm(val algorithm: String) : ChallengeSignerError

    /**
     * Indicates that the worker private key was parsed successfully, but the signature operation failed.
     *
     * @property reason Human-readable signing failure reason.
     */
    data class SigningFailed(val reason: String) : ChallengeSignerError
}

