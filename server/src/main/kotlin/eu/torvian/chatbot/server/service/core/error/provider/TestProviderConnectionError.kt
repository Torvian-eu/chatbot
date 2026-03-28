package eu.torvian.chatbot.server.service.core.error.provider

/**
 * Represents logical errors that can occur while testing provider connectivity.
 */
sealed interface TestProviderConnectionError {
    /**
     * Indicates the input payload is invalid.
     *
     * @property reason Human-readable input validation failure details.
     */
    data class InvalidInput(val reason: String) : TestProviderConnectionError

    /**
     * Indicates provider authentication failed.
     *
     * @property reason Human-readable authentication failure details.
     */
    data class AuthenticationFailed(val reason: String) : TestProviderConnectionError

    /**
     * Indicates provider configuration is invalid for the requested provider type.
     *
     * @property reason Human-readable configuration failure details.
     */
    data class InvalidConfiguration(val reason: String) : TestProviderConnectionError

    /**
     * Indicates provider returned a non-success API status.
     *
     * @property statusCode HTTP status returned by the provider.
     * @property reason Human-readable provider API error summary.
     */
    data class ProviderApiError(val statusCode: Int, val reason: String) : TestProviderConnectionError

    /**
     * Indicates provider returned an invalid or unparseable discovery payload.
     *
     * @property reason Human-readable response parsing/validation failure details.
     */
    data class InvalidProviderResponse(val reason: String) : TestProviderConnectionError

    /**
     * Indicates provider is unreachable.
     *
     * @property reason Human-readable connectivity failure details.
     */
    data class ProviderUnavailable(val reason: String) : TestProviderConnectionError
}

