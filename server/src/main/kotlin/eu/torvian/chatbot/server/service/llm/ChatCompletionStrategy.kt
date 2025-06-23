package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import eu.torvian.chatbot.common.models.*

/**
 * Defines the interface for provider-specific chat completion logic.
 * Each implementation handles the API details for a particular LLMProviderType,
 * mapping between generic application models and provider-specific API formats.
 * Strategies should be independent of the HTTP client implementation (e.g., Ktor).
 */
interface ChatCompletionStrategy {
    /**
     * Prepares the raw data structure and configuration for the API request.
     * This includes mapping input models (ChatMessage, ModelSettings etc.)
     * to the API's request DTO, determining the path, method, content type,
     * and specifying any custom headers (like authentication).
     * The returned 'body' object must be serializable by the client.
     *
     * @param messages The conversation context as a list of ChatMessage.
     * @param modelConfig Details of the specific LLM model being used.
     * @param provider Configuration of the LLM provider, including base URL and type.
     * @param settings Specific generation settings for this request.
     * @param apiKey The API key string, if required by the provider and available.
     * @return Either a [LLMCompletionError.ConfigurationError] if preparation fails (e.g., missing key),
     *         or the [ApiRequestConfig] containing details for the HTTP call.
     */
    fun prepareRequest(
        messages: List<ChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?
    ): Either<LLMCompletionError.ConfigurationError, ApiRequestConfig>

    /**
     * Processes a raw successful API response body string into the generic result ([LLMCompletionResult]).
     * The strategy is responsible for knowing the expected JSON (or other) structure of a successful
     * response for its provider and extracting/mapping the relevant data.
     *
     * @param responseBody The raw string body of the successful HTTP response (status 2xx).
     * @return Either an [LLMCompletionError.InvalidResponseError] if parsing or mapping fails,
     *         or the generic [LLMCompletionResult].
     */
    fun processSuccessResponse(responseBody: String): Either<LLMCompletionError.InvalidResponseError, LLMCompletionResult>

    /**
     * Processes a raw error API response body string and status code into a specific generic error ([LLMCompletionError]).
     * The strategy is responsible for parsing API-specific error formats from the body string
     * and providing a meaningful error message or type.
     *
     * @param statusCode The HTTP status code of the error response (e.g., 400, 401, 500).
     * @param errorBody The raw string body of the error HTTP response.
     * @return A specific [LLMCompletionError] representing the API error.
     */
    fun processErrorResponse(statusCode: Int, errorBody: String): LLMCompletionError

    /**
     * Identifies the [LLMProviderType] this strategy handles. Used by the client to select the correct strategy.
     */
    val providerType: LLMProviderType
}
