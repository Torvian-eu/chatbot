package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType

/**
 * Defines provider-specific model discovery logic.
 *
 * @property providerType Identifies which [LLMProviderType] this strategy supports.
 */
interface ModelDiscoveryStrategy {
    /**
     * Prepares the API request configuration for model discovery.
     *
     * @param provider Provider configuration used to build the request target and auth rules.
     * @param apiKey Decrypted provider credential when required by the provider type.
     * @return Either a [ModelDiscoveryError.ConfigurationError] or a prepared [ApiRequestConfig].
     */
    fun prepareRequest(
        provider: LLMProvider,
        apiKey: String?
    ): Either<ModelDiscoveryError.ConfigurationError, ApiRequestConfig>

    /**
     * Processes a successful discovery response.
     *
     * @param responseBody Raw response body returned by the provider for a successful request.
     * @return Either a [ModelDiscoveryError.InvalidResponseError] or parsed [ModelDiscoveryResult].
     */
    fun processSuccessResponse(responseBody: String): Either<ModelDiscoveryError.InvalidResponseError, ModelDiscoveryResult>

    /**
     * Processes an error discovery response.
     *
     * @param statusCode HTTP status code returned by the provider.
     * @param errorBody Raw error response body returned by the provider.
     * @return Mapped logical [ModelDiscoveryError].
     */
    fun processErrorResponse(statusCode: Int, errorBody: String): ModelDiscoveryError

    /**
     * Identifies the provider type this discovery strategy supports.
     */
    val providerType: LLMProviderType
}

