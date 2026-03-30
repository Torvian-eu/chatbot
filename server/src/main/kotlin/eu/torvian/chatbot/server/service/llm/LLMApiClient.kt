package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Interface for interacting with external LLM APIs.
 * Uses provider-agnostic generic models ([LLMCompletionResult], [LLMCompletionError])
 * for request results, hiding the provider-specific API details from the caller.
 */
interface LLMApiClient {
    /**
     * Sends a non-streaming chat completion request to the appropriate LLM API.
     * Delegates the API-specific details (request format, response parsing) to an internal strategy.
     *
     * @param messages The conversation context as a list of RawChatMessage
     * @param modelConfig Configuration details for the target LLM model.
     * @param provider Provider configuration containing base URL and type information.
     * @param settings Specific settings profile to use for this completion request.
     * @param apiKey The decrypted API key for authentication (nullable if not required by the provider).
     * @param tools Optional list of tool definitions that the model can call
     * @return Either an [LLMCompletionError] if the process fails at any stage (configuration, network, API error, parsing),
     *         or the generic [LLMCompletionResult] on success.
     */
    suspend fun completeChat(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>? = null
    ): Either<LLMCompletionError, LLMCompletionResult>

    /**
     * Sends a streaming chat completion request to the appropriate LLM API.
     * Delegates API-specific details to a strategy.
     *
     * @param messages The conversation context as a list of RawChatMessage
     * @param modelConfig Configuration details for the target LLM model.
     * @param provider Provider configuration containing base URL and type information.
     * @param settings Specific settings profile to use for this completion request.
     * @param apiKey The decrypted API key for authentication (nullable if not required by the provider).
     * @param tools Optional list of tool definitions that the model can call
     * @return A Flow of Either<LLMCompletionError, LLMStreamChunk> representing the stream.
     *         An error emitted in the Flow indicates a problem during the stream.
     *         The flow terminates with `LLMStreamChunk.Done` on success or an error.
     */
    fun completeChatStreaming(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>? = null
    ): Flow<Either<LLMCompletionError, LLMStreamChunk>>

    /**
     * Discovers available remote models for the given provider.
     * Delegates provider-specific request/response handling to a model discovery strategy.
     *
     * @param provider Provider configuration containing base URL and type information.
     * @param apiKey The decrypted API key for authentication (nullable if not required by the provider).
     * @return Either a [ModelDiscoveryError] when discovery fails, or a [ModelDiscoveryResult] on success.
     */
    suspend fun discoverModels(
        provider: LLMProvider,
        apiKey: String?
    ): Either<ModelDiscoveryError, ModelDiscoveryResult>
}
