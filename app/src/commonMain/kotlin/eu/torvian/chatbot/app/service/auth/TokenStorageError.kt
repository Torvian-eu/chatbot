package eu.torvian.chatbot.app.service.auth

/**
 * Defines a hierarchy of errors that can occur during token storage operations.
 * These errors follow the same pattern as ApiResourceError and represent
 * logical errors in token management operations.
 */
sealed class TokenStorageError {
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
     * Represents an I/O error that occurred during token storage operations.
     * Examples include file system access failures, permission issues, or disk space problems.
     *
     * @property message A descriptive message about the I/O error.
     * @property cause The underlying [Throwable] (e.g., IOException).
     */
    data class IOError(
        override val message: String,
        override val cause: Throwable? = null
    ) : TokenStorageError()

    /**
     * Represents an error that occurred during token encryption or decryption operations.
     * Examples include invalid keys, corrupted encrypted data, or cryptographic failures.
     *
     * @property message A descriptive message about the encryption error.
     * @property cause The underlying [Throwable] (e.g., cryptographic exceptions).
     */
    data class EncryptionError(
        override val message: String,
        override val cause: Throwable? = null
    ) : TokenStorageError()

    /**
     * Represents a case where a requested token was not found in storage.
     * This is a logical error indicating the token has not been stored or has been cleared.
     *
     * @property message A descriptive message about the missing token.
     */
    data class NotFound(
        override val message: String = "Token not found"
    ) : TokenStorageError() {
        override val cause: Throwable? = null
    }

    /**
     * Represents an error in the format or structure of stored token data.
     * This occurs when stored data cannot be parsed or is corrupted.
     *
     * @property message A descriptive message about the format error.
     * @property cause The underlying [Throwable] (e.g., JSON parsing exceptions).
     */
    data class InvalidTokenFormat(
        override val message: String,
        override val cause: Throwable? = null
    ) : TokenStorageError()

    /**
     * Represents any other unexpected or unhandled error in token storage operations.
     *
     * @property message A descriptive message about the unknown error.
     * @property cause The underlying [Throwable] if available.
     */
    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : TokenStorageError()
}
