package eu.torvian.chatbot.server.service.llm

/**
 * Sealed class representing a single chunk of data in a streaming LLM response.
 * This is an internal representation, parsed by strategies from raw API responses.
 */
sealed class LLMStreamChunk {
    /** Represents a content delta chunk. */
    data class ContentChunk(val deltaContent: String, val finishReason: String? = null) : LLMStreamChunk()

    /** Represents a usage statistics chunk (might be sent at the end). */
    data class UsageChunk(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : LLMStreamChunk()

    /** Represents the final "done" signal from the LLM. */
    data object Done : LLMStreamChunk()

    /** Represents an error encountered *during* streaming by the LLM API itself. */
    data class Error(val llmError: LLMCompletionError) : LLMStreamChunk()
}
