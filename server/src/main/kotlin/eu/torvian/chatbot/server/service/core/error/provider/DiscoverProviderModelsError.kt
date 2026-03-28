package eu.torvian.chatbot.server.service.core.error.provider

/**
 * Represents logical errors that can occur while discovering remote provider models.
 */
sealed interface DiscoverProviderModelsError {
    /**
     * Indicates the provider was not found.
     *
     * @property id Missing provider ID.
     */
    data class ProviderNotFound(val id: Long) : DiscoverProviderModelsError

    /**
     * Indicates the configured provider credential alias was not found in secure storage.
     *
     * @property alias Credential alias that could not be resolved.
     */
    data class CredentialNotFound(val alias: String) : DiscoverProviderModelsError

    /**
     * Indicates provider authentication failed.
     *
     * @property reason Human-readable failure reason.
     */
    data class AuthenticationFailed(val reason: String) : DiscoverProviderModelsError

    /**
     * Indicates provider configuration is invalid for model discovery.
     *
     * @property reason Human-readable configuration error.
     */
    data class InvalidConfiguration(val reason: String) : DiscoverProviderModelsError

    /**
     * Indicates provider returned a non-success API error.
     *
     * @property statusCode HTTP status returned by the provider.
     * @property reason Human-readable provider error summary.
     */
    data class ProviderApiError(val statusCode: Int, val reason: String) : DiscoverProviderModelsError

    /**
     * Indicates provider returned an invalid or unparseable payload.
     *
     * @property reason Human-readable parsing/validation failure details.
     */
    data class InvalidProviderResponse(val reason: String) : DiscoverProviderModelsError

    /**
     * Indicates remote provider is currently unreachable.
     *
     * @property reason Human-readable connectivity failure details.
     */
    data class ProviderUnavailable(val reason: String) : DiscoverProviderModelsError
}

