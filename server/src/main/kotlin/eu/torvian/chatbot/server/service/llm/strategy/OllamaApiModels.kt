package eu.torvian.chatbot.server.service.llm.strategy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
         * Can also include tool calls when the model decides to use tools.
         *
         * @property role Role string (e.g., "assistant")
         * @property content The generated text. Nullable when tool_calls is present without text.
         * @property tool_calls List of tool calls made by the assistant. Present when the model decides to use tools.
         */
        @Serializable
        data class Message(
            val role: String,
            val content: String? = null,
            val tool_calls: List<ToolCall>? = null
        )
    }

    /**
     * Represents a single chunk received during a streaming chat completion from Ollama.
     * Each line in an Ollama stream is a JSON object matching this structure (except for the final 'done: true' object).
     * The 'done' property indicates if the stream is finished.
     * The 'message' property contains the delta content.
     */
    @Serializable
    data class ChatCompletionStreamResponse(
        val model: String,
        val created_at: String,
        val message: Message? = null, // message can be null for the final done chunk (which contains stats)
        val done: Boolean,
        val total_duration: Long? = null,
        val load_duration: Long? = null,
        val prompt_eval_count: Int? = null,
        val prompt_eval_duration: Long? = null,
        val eval_count: Int? = null,
        val eval_duration: Long? = null
    ) {
        /**
         * Represents a message within a streaming chunk.
         *
         * @property role Role string
         * @property content The text content delta. Nullable when tool_calls is present.
         * @property tool_calls Tool calls in this chunk. Note: Ollama may or may not support streaming tool calls incrementally.
         */
        @Serializable
        data class Message(
            val role: String,
            val content: String? = null,
            val tool_calls: List<ToolCall>? = null
        )
    }

    /**
     * Represents a chat completion request to Ollama API.
     * Matches the structure documented by Ollama for non-streaming requests.
     *
     * Note: The OllamaChatStrategy now builds requests dynamically using JsonObject.
     * This DTO is kept for reference and for parsing responses which may share this structure,
     * but request creation uses a more flexible JsonObject approach.
     *
     * @property model The specific model name string (e.g., "llama3.2")
     * @property messages The conversation history
     * @property stream Whether to stream the response
     * @property options Additional model parameters
     * @property tools List of tools the model may call. Requires models with tool calling support. Uses OpenAI-compatible format.
     */
    @Serializable
    data class ChatCompletionRequest(
        val model: String,
        val messages: List<RequestMessage>,
        val stream: Boolean = true,
        val options: Options? = null,
        val tools: List<ToolDefinition>? = null
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
     * Represents a tool definition in Ollama API format.
     * Tools are external functions that the model can choose to call.
     * This is separate from the domain ToolDefinition model and represents the API format.
     *
     * @property type The type of the tool. Always "function" for Ollama.
     * @property function The function definition
     */
    @Serializable
    data class ToolDefinition(
        val type: String = "function",
        val function: FunctionDefinition
    ) {
        /**
         * Represents a function that can be called by the model.
         *
         * @property name The name of the function to be called. Must be a-z, A-Z, 0-9, or contain underscores and dashes, with a maximum length of 64.
         * @property description A description of what the function does, used by the model to choose when and how to call the function.
         * @property parameters The parameters the function accepts, described as a JSON Schema object. See json-schema.org for details.
         * @property strict Whether to enable strict schema adherence when generating the function call. If true, the model will follow the exact schema. Default is false.
         */
        @Serializable
        data class FunctionDefinition(
            val name: String,
            val description: String? = null,
            val parameters: JsonObject? = null,
            val strict: Boolean? = false
        )
    }

    /**
     * Represents a tool call in Ollama format (OpenAI-compatible).
     * This appears in the assistant's response when the model decides to call a tool.
     *
     * @property function The function call details
     */
    @Serializable
    data class ToolCall(
        val function: FunctionCall
    ) {
        /**
         * Represents the function being called and its arguments.
         *
         * @property name The name of the function to call
         * @property arguments The arguments to call the function with, as a JSON string generated by the model.
         *                     Note: This may not be valid JSON, and arguments may be hallucinated by the model.
         *                     It's nullable to support parameterless functions.
         */
        @Serializable
        data class FunctionCall(
            val name: String,
            val arguments: String? = null
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
