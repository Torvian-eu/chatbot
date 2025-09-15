package eu.torvian.chatbot.server.service.llm.strategy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data Transfer Objects for OpenAI-compatible API responses and requests.
 * These models represent the structure of communication with external OpenAI or compatible APIs.
 */
object OpenAiApiModels {

    /**
     * Represents a chat completion response from an OpenAI-compatible API.
     * Matches the structure documented by OpenAI for non-streaming responses.
     *
     * @property id Unique identifier for the completion
     * @property object The object type (typically "chat.completion")
     * @property created Unix timestamp of when the completion was created
     * @property model The model name used by the API
     * @property choices List of generated responses
     * @property usage Token usage statistics
     */
    @Serializable
    data class ChatCompletionResponse(
        val id: String,
        @SerialName("object")
        val `object`: String,
        val created: Long,
        val model: String,
        val choices: List<Choice>,
        val usage: Usage
    ) {
        /**
         * Represents a single choice in the chat completion response.
         *
         * @property index Index of the choice (0 for the first)
         * @property message The generated message
         * @property finish_reason Reason generation stopped (e.g., "stop", "length")
         */
        @Serializable
        data class Choice(
            val index: Int,
            val message: Message,
            val finish_reason: String
        ) {
            /**
             * Represents a message within a choice.
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
         * Represents token usage information for the completion.
         *
         * @property prompt_tokens Tokens in the input prompt
         * @property completion_tokens Tokens in the generated completion
         * @property total_tokens Total tokens used (prompt + completion)
         */
        @Serializable
        data class Usage(
            val prompt_tokens: Int,
            val completion_tokens: Int,
            val total_tokens: Int
        )
    }

    /**
     * Represents a chat completion request to an OpenAI-compatible API.
     *
     * Note: The OpenAIChatStrategy now builds a JsonObject dynamically to support
     * any custom parameter via ChatModelSettings.customParams, rather than being
     * limited to this DTO's fields. This DTO is kept for reference and potential other uses.
     *
     * @property model Model name/identifier to use
     * @property messages List of conversation messages
     * @property stream Whether to stream the response
     * @property temperature Sampling temperature
     * @property max_tokens Max tokens to generate
     * @property top_p Nucleus sampling
     * @property frequency_penalty Frequency penalty parameter
     * @property presence_penalty Presence penalty parameter
     * @property stop Stop sequences
     * @property seed Seed for deterministic generation
     */
    @Serializable
    data class ChatCompletionRequest(
        val model: String,
        val messages: List<RequestMessage>,
        val stream: Boolean = false, // Added for completeness
        val temperature: Float? = null,
        val max_tokens: Int? = null,
        val top_p: Float? = null,
        val frequency_penalty: Float? = null,
        val presence_penalty: Float? = null,
        val stop: List<String>? = null,
        val seed: Int? = null // Added for completeness
    ) {
        /**
         * Represents a message in the chat completion request.
         * Role string must match API expectations ("system", "user", "assistant", "tool", "function").
         *
         * @property role Role string (e.g., "system", "user", "assistant")
         * @property content Message content
         */
        @Serializable
        data class RequestMessage(
            val role: String,
            val content: String
        )
    }

    /**
     * Represents a single chunk received during a streaming chat completion from an OpenAI-compatible API.
     * This follows the Server-Sent Events (SSE) format where each 'data:' line contains a JSON object
     * matching this structure.
     *
     * @property id The unique identifier for the chat completion.
     * @property object The object type, typically "chat.completion.chunk".
     * @property created Unix timestamp for when the chunk was created.
     * @property model The model that generated the response.
     * @property choices A list of choices, each containing a delta of the response.
     * @property usage Token usage statistics.
     */
    @Serializable
    data class ChatCompletionStreamChunk(
        val id: String,
        @SerialName("object")
        val `object`: String,
        val created: Long,
        val model: String,
        val choices: List<StreamChoice>,
        val usage: StreamUsage? = null
    ) {
        /**
         * Represents a single choice within a stream chunk.
         *
         * @property index The index of the choice.
         * @property delta The incremental update to the message.
         * @property finish_reason The reason the model stopped generating tokens, present in the final delta for a choice.
         */
        @Serializable
        data class StreamChoice(
            val index: Int,
            val delta: Delta,
            val finish_reason: String? = null
        ) {
            /**
             * The actual content delta. One of these fields will typically be non-null in any given chunk.
             *
             * @property role The role of the author of this message, usually "assistant" and present only in the first chunk.
             * @property content The text content delta.
             */
            @Serializable
            data class Delta(
                val role: String? = null,
                val content: String? = null
            )
        }

        /**
         * Represents token usage statistics in a streaming chunk.
         * This can appear in any chunk when stream_options.include_usage is true.
         *
         * @property completion_tokens Number of tokens in the generated completion
         * @property prompt_tokens Number of tokens in the prompt/context
         * @property total_tokens Total tokens used (prompt + completion)
         */
        @Serializable
        data class StreamUsage(
            val completion_tokens: Int,
            val prompt_tokens: Int,
            val total_tokens: Int
        )
    }

    // --- DTO for OpenAI specific error response ---
    /**
     * Represents the common structure of an error response from OpenAI.
     * Used by strategies to parse error details from raw error bodies.
     * Example: {"error": {"message": "...", "type": "...", ...}}
     *
     * @property error The error details object
     */
    @Serializable
    data class OpenAiErrorResponse(@SerialName("error") val error: OpenAiErrorDetail) {
        /**
         * Represents the detailed error information from OpenAI.
         *
         * @property message The error message
         * @property type The error type (e.g., "invalid_request_error")
         * @property param The parameter that caused the error, if applicable
         * @property code The error code, if applicable
         */
        @Serializable
        data class OpenAiErrorDetail(
            val message: String,
            val type: String?,
            val param: String?,
            val code: String?
        )
    }
}