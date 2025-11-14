package eu.torvian.chatbot.server.service.llm

/**
 * Sealed class representing a single chunk of data in a streaming LLM response.
 * This is an internal representation, parsed by strategies from raw API responses.
 */
sealed class LLMStreamChunk {
    /**
     * Represents a content delta chunk.
     *
     * @property deltaContent The incremental text content
     * @property finishReason The reason the model stopped generating tokens (present in final chunk)
     */
    data class ContentChunk(val deltaContent: String, val finishReason: String? = null) : LLMStreamChunk()

    /**
     * Represents a tool call chunk during streaming.
     * Tool calls are built incrementally; multiple chunks with the same index should be accumulated.
     *
     * @property index The index of this tool call in the list of tool calls being made. Null for providers that don't use indexed deltas.
     * @property id The unique identifier for this tool call. Present in the first chunk for this index (OpenAI). Null for Ollama.
     * @property name The name of the function to call. Present in the first chunk for this index.
     * @property argumentsDelta Incremental arguments string. Multiple deltas should be concatenated to form the complete arguments JSON string.
     */
    data class ToolCallChunk(
        val index: Int?,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?
    ) : LLMStreamChunk()

    /**
     * Represents a usage statistics chunk (might be sent at the end).
     *
     * @property promptTokens Number of tokens in the prompt/context
     * @property completionTokens Number of tokens in the generated completion
     * @property totalTokens Total tokens used (prompt + completion)
     */
    data class UsageChunk(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : LLMStreamChunk()

    /**
     * Represents the final "done" signal from the LLM.
     */
    data object Done : LLMStreamChunk()

    /**
     * Represents an error encountered *during* streaming by the LLM API itself.
     *
     * @property llmError The error that occurred
     */
    data class Error(val llmError: LLMCompletionError) : LLMStreamChunk()
}
