package eu.torvian.chatbot.server.service.llm

/**
 * Sealed class representing possible errors during model discovery requests.
 */
sealed class ModelDiscoveryError {
    /**
     * Error related to network communication failures.
     *
     * @property message Human-readable explanation of the network failure.
     * @property cause Underlying throwable when available.
     */
    data class NetworkError(val message: String, val cause: Throwable?) : ModelDiscoveryError()

    /**
     * Error returned by the provider API (non-2xx HTTP status).
     *
     * @property statusCode HTTP status code returned by the provider.
     * @property message Parsed provider error message when available.
     * @property errorBody Raw provider response body for diagnostics.
     */
    data class ApiError(val statusCode: Int, val message: String?, val errorBody: String?) : ModelDiscoveryError()

    /**
     * Error indicating that a successful response could not be parsed.
     *
     * @property message Human-readable parsing failure description.
     * @property cause Underlying throwable when available.
     */
    data class InvalidResponseError(val message: String, val cause: Throwable? = null) : ModelDiscoveryError()

    /**
     * Error indicating authentication failed.
     *
     * @property message Human-readable authentication failure description.
     */
    data class AuthenticationError(val message: String = "Authentication failed") : ModelDiscoveryError()

    /**
     * Error indicating invalid client/provider configuration.
     *
     * @property message Human-readable configuration failure description.
     */
    data class ConfigurationError(val message: String) : ModelDiscoveryError()
}

