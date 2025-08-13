package eu.torvian.chatbot.server.service.llm.strategy

import kotlinx.serialization.Serializable

/**
 * Data Transfer Objects for Ollama API responses and requests.
 * These models represent the structure of communication with Ollama's local API.
 * Based on Ollama API documentation: https://ollama.readthedocs.io/en/api/
 */
object OllamaApiModels {

    /**
     * Represents a chat completion response from Ollama API.
     * Matches the structure documented by Ollama for non-streaming responses.
     *
     * @property model The model name used by the API
     * @property created_at ISO 8601 timestamp of when the completion was created
     * @property message The generated message
     * @property done Whether the response is complete
     * @property total_duration Total time spent generating the response (nanoseconds)
     * @property load_duration Time spent loading the model (nanoseconds)
     * @property prompt_eval_count Number of tokens in the prompt
     * @property prompt_eval_duration Time spent evaluating the prompt (nanoseconds)
     * @property eval_count Number of tokens in the response
     * @property eval_duration Time spent generating the response (nanoseconds)
     */
    @Serializable
    data class ChatCompletionResponse(
        val model: String,
        val created_at: String,
        val message: Message,
        val done: Boolean,
        val total_duration: Long? = null,
        val load_duration: Long? = null,
        val prompt_eval_count: Int? = null,
        val prompt_eval_duration: Long? = null,
        val eval_count: Int? = null,
        val eval_duration: Long? = null
    ) {
        /**
         * Represents a message within the chat completion response.
         * Contains the role and content of the generated message.
         *
         * @property role Role string (e.g., "assistant")
         * @property content The generated text
         */
        @Serializable
        data class Message(
            val role: String,
            val content: String
        )
    }

    /**
     * Represents a chat completion request to Ollama API.
     * Matches the structure documented by Ollama for non-streaming requests.
     *
     * @property model The specific model name string (e.g., "llama3.2")
     * @property messages The conversation history
     * @property stream Whether to stream the response
     * @property options Additional model parameters
     */
    @Serializable
    data class ChatCompletionRequest(
        val model: String,
        val messages: List<RequestMessage>,
        val stream: Boolean = true,
        val options: Options? = null
    ) {
        /**
         * Represents a message in the chat completion request.
         * Role string must match Ollama API expectations ("system", "user", "assistant").
         *
         * @property role Role string (e.g., "system", "user", "assistant")
         * @property content Message content
         */
        @Serializable
        data class RequestMessage(
            val role: String,
            val content: String
        )

        /**
         * Represents additional options for the Ollama model.
         * These correspond to the model parameters that can be set.
         *
         * @property temperature Sampling temperature
         * @property top_p Nucleus sampling
         * @property top_k Top-k sampling
         * @property num_predict Maximum number of tokens to generate
         * @property stop Stop sequences
         * @property seed Random seed for reproducible outputs
         */
        @Serializable
        data class Options(
            val temperature: Float? = null,
            val top_p: Float? = null,
            val top_k: Int? = null,
            val num_predict: Int? = null,
            val stop: List<String>? = null,
            val seed: Int? = null
        )
    }

    /**
     * Represents the common structure of an error response from Ollama.
     * Used by strategies to parse error details from raw error bodies.
     *
     * @property error The error message
     */
    @Serializable
    data class OllamaErrorResponse(
        val error: String
    )
}
