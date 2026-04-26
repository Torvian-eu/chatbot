package eu.torvian.chatbot.worker.setup

/**
 * Logical failures that can happen while preparing worker setup artifacts.
 */
sealed interface WorkerSetupError {
    /**
     * Raised when setup cannot obtain credentials from environment variables or interactive prompt.
     *
     * @property reason Human-readable explanation for why credentials could not be resolved.
     */
    data class CredentialsUnavailable(val reason: String) : WorkerSetupError

    /**
     * Raised when setup cannot obtain a display name from environment variables or interactive prompt.
     *
     * @property reason Human-readable explanation for why the display name could not be resolved.
     */
    data class DisplayNameUnavailable(val reason: String) : WorkerSetupError

    /**
     * Raised when the worker setup is started without a server URL, but the merged worker config cannot supply one.
     *
     * @property configPath Config directory that would have been used.
     */
    data class ServerUrlMissing(val configPath: String) : WorkerSetupError

    /**
     * Raised when the setup config layer exists but cannot be read.
     *
     * @property path `setup.json` path.
     * @property reason Human-readable read failure description.
     */
    data class ConfigReadFailed(val path: String, val reason: String) : WorkerSetupError

    /**
     * Raised when the setup config content is malformed.
     *
     * @property path `setup.json` path.
     * @property reason Human-readable parse or validation description.
     */
    data class ConfigInvalid(val path: String, val reason: String) : WorkerSetupError

    /**
     * Raised when an existing secrets file is present but invalid.
     *
     * @property path Secrets file path.
     * @property reason Human-readable validation failure description.
     */
    data class SecretsInvalid(val path: String, val reason: String) : WorkerSetupError

    /**
     * Raised when a fresh self-signed certificate cannot be generated.
     *
     * @property reason Human-readable certificate generation failure description.
     */
    data class CertificateGenerationFailed(val reason: String) : WorkerSetupError

    /**
     * Raised when login to the server fails during setup.
     *
     * @property reason Human-readable login failure description.
     */
    data class LoginFailed(val reason: String) : WorkerSetupError

    /**
     * Raised when worker registration fails after certificate generation.
     *
     * @property reason Human-readable registration failure description.
     */
    data class WorkerRegistrationFailed(val reason: String) : WorkerSetupError

    /**
     * Raised when logout fails after setup login has succeeded.
     *
     * @property reason Human-readable logout failure description.
     */
    data class LogoutFailed(val reason: String) : WorkerSetupError

    /**
     * Raised when setup receives an unexpected HTTP response status from the server.
     *
     * @property operation Human-readable operation name.
     * @property statusCode HTTP status code returned by the server.
     * @property description Optional response description/body preview.
     */
    data class UnexpectedHttpStatus(
        val operation: String,
        val statusCode: Int,
        val description: String? = null
    ) : WorkerSetupError

    /**
     * Raised when setup encounters a non-HTTP transport failure (DNS, timeout, TLS, serialization, etc.).
     *
     * @property operation Human-readable operation name.
     * @property reason Human-readable failure detail.
     */
    data class TransportFailure(val operation: String, val reason: String) : WorkerSetupError

    /**
     * Raised when a setup file cannot be written.
     *
     * @property path Target file path.
     * @property reason Human-readable write failure description.
     */
    data class FileWriteFailed(val path: String, val reason: String) : WorkerSetupError
}

