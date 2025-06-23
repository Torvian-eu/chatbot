package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.ModelSettings

/**
 * Interface for interacting with external LLM APIs.
 * Uses provider-agnostic generic models ([LLMCompletionResult], [LLMCompletionError])
 * for request results, hiding the provider-specific API details from the caller.
 */
interface LLMApiClient {
    /**
     * Sends a chat completion request to the appropriate LLM API based on the provider type.
     * Delegates the API-specific details (request format, response parsing) to an internal strategy.
     *
     * @param messages The list of messages forming the conversation context.
     * @param modelConfig Configuration details for the target LLM model.
     * @param provider Provider configuration containing base URL and type information.
     * @param settings Specific settings profile to use for this completion request.
     * @param apiKey The decrypted API key for authentication (nullable if not required by the provider).
     * @return Either an [LLMCompletionError] if the process fails at any stage (configuration, network, API error, parsing),
     *         or the generic [LLMCompletionResult] on success.
     */
    suspend fun completeChat(
        messages: List<ChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?
    ): Either<LLMCompletionError, LLMCompletionResult>
}
