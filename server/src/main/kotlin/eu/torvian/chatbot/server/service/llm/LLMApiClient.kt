package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.domain.llm.OpenAiApiModels.ChatCompletionResponse

/**
 * Interface for interacting with external LLM APIs (OpenAI-compatible).
 */
interface LLMApiClient {
    /**
     * Sends a chat completion request to the LLM API.
     * @param messages The list of messages forming the conversation context.
     * @param modelConfig Configuration details for the target LLM model.
     * @param provider Provider configuration containing base URL and type information.
     * @param settings Specific settings profile to use for this completion request.
     * @param apiKey The decrypted API key for authentication (nullable if not required).
     * @return Either an error string or the response from the LLM API.
     */
    suspend fun completeChat(
        messages: List<ChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?
    ): Either<String, ChatCompletionResponse>
}
