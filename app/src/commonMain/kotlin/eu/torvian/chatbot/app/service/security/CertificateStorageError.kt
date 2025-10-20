package eu.torvian.chatbot.app.service.security

/**
 * Defines a hierarchy of errors that can occur during certificate storage operations.
 * These errors follow the same pattern as TokenStorageError and represent
 * logical errors in certificate management operations.
 */
sealed class CertificateStorageError {
    abstract val message: String
    abstract val cause: Throwable?

    /**
     * Represents an I/O error that occurred during certificate storage operations.
     * Examples include file system access failures, permission issues, or disk space problems.
     */
    data class IOError(
        override val message: String,
        override val cause: Throwable? = null
    ) : CertificateStorageError()

    /**
     * Represents a case where a requested certificate for a server was not found in storage.
     */
    data class NotFound(
        override val message: String = "Certificate not found for this server."
    ) : CertificateStorageError() {
        override val cause: Throwable? = null
    }

    /**
     * Represents an error in the format or structure of stored certificate data.
     * This occurs when stored data cannot be parsed or is corrupted.
     */
    data class InvalidFormat(
        override val message: String,
        override val cause: Throwable? = null
    ) : CertificateStorageError()

    /**
     * Represents any other unexpected or unhandled error in certificate storage operations.
     *
     * @property message A descriptive message about the unknown error.
     * @property cause The underlying [Throwable] if available.
     */
    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : CertificateStorageError()
}

