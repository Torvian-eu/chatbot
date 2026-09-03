package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Defines the interface for provider-specific chat completion logic.
 * Each implementation handles the API details for a particular LLMProviderType,
 * mapping between generic application models and provider-specific API formats.
 * Strategies should be independent of the HTTP client implementation (e.g., Ktor).
 */
interface ChatCompletionStrategy {
    /**
     * Prepares the raw data structure and configuration for the API request.
     * This includes mapping input models (RawChatMessage, ModelSettings, ToolDefinition etc.)
     * to the API's request DTO, determining the path, method, content type,
     * and specifying any custom headers (like authentication).
     * The returned 'body' object must be serializable by the client.
     *
     * @param messages The conversation context as a list of RawChatMessage.
     *                 These are simplified messages without threading information,
     *                 suitable for LLM API communication.
     * @param modelConfig Details of the specific LLM model being used.
     * @param provider Configuration of the LLM provider, including base URL and type.
     * @param settings Specific generation settings for this request.
     * @param apiKey The API key string, if required by the provider and available.
     * @param tools Optional list of tool definitions that the model can call.
     *              Only applicable for models with tool calling capability.
     *              The strategy will map these domain ToolDefinitions to the
     *              provider-specific tool format (e.g., OpenAI function format).
     * @param systemMessage Optional composed system prompt. This is the single source of truth for the
     *              system message: when non-blank it is injected as the system message, and the strategy
     *              must NOT read the settings' own system text. When null/blank, no system message is sent.
     * @return Either a [LLMCompletionError.ConfigurationError] if preparation fails (e.g., missing key),
     *         or the [ApiRequestConfig] containing details for the HTTP call.
     */
    fun prepareRequest(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>? = null,
        systemMessage: String? = null
    ): Either<LLMCompletionError.ConfigurationError, ApiRequestConfig>

    /**
     * Builds the compact, input-only JSON projection of the request that would be sent to the provider.
     *
     * The projection contains exactly the wire fields that consume conversational input context
     * (system/instructions, messages/input items, tool definitions, and provider-required wrappers
     * such as `tool_choice`), excluding generation/output settings (`model`, `stream`, sampling
     * parameters, `store`, etc.). Request preparation must embed these exact fields into the request
     * body, and the repository-owned token counter must serialize this projection, so the counted
     * input can never drift from the actual provider payload.
     *
     * @param messages The conversation context as a list of RawChatMessage.
     * @param modelConfig Details of the specific LLM model being used.
     * @param provider Configuration of the LLM provider, including base URL and type.
     * @param settings Specific generation settings for this request.
     * @param systemMessage Optional composed system prompt (single source of truth). When non-blank
     *            it is injected as the system message/instructions field; when null/blank it is omitted.
     * @param tools Optional list of tool definitions. When non-empty the projection includes the
     *            provider tool format and (for dialects that support it) `tool_choice`.
     * @return Either a [LLMCompletionError.ConfigurationError] if the settings subtype is unsupported
     *         by this strategy, or the input projection object.
     */
    fun buildInputProjection(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        systemMessage: String? = null,
        tools: List<ToolDefinition>? = null
    ): Either<LLMCompletionError.ConfigurationError, JsonObject>

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
     * Processes a raw streaming API response (as a Flow of strings/bytes) into a generic stream of LLMStreamChunk.
     * The strategy is responsible for parsing each raw chunk according to its provider's streaming format
     * (e.g., SSE for OpenAI, NDJSON for Ollama) and mapping it to LLMStreamChunk.
     *
     * @param responseStream A Flow of raw string chunks from the HTTP response.
     * @return A Flow of Either<LLMCompletionError.InvalidResponseError, LLMStreamChunk>
     */
    fun processStreamingResponse(
        responseStream: Flow<String>
    ): Flow<Either<LLMCompletionError.InvalidResponseError, LLMStreamChunk>>

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
