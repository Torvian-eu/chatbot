package eu.torvian.chatbot.server.service.llm

import eu.torvian.chatbot.common.models.core.UsageStats
import kotlinx.serialization.json.JsonObject

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
     * Represents a reasoning-text delta emitted during streaming for a reasoning-capable model.
     *
     * This chunk carries the **plaintext** chain-of-thought text produced by the model, in incremental
     * deltas, and is intended for live UI rendering (e.g. a collapsible reasoning panel). Consumers that
     * group chunks by ([outputIndex], [contentIndex]) and concatenate their [delta] values reconstruct the
     * full text of a reasoning content part.
     *
     * Unlike [ReasoningDone] (which carries the opaque reasoning output item for persistence and replay),
     * this chunk carries only renderable text. It is displayed live and the accumulated text is persisted as a
     * derived plaintext reasoning item, but the raw chunk is never fed back into a future request's `input`.
     *
     * @property outputIndex The output index of the reasoning output item this delta belongs to, or `null`
     *            when the provider does not include it.
     * @property contentIndex The index of the reasoning content part within the item this delta belongs to.
     * @property delta The incremental reasoning text added. Multiple deltas should be concatenated.
     */
    data class ReasoningTextChunk(
        val outputIndex: Int?,
        val contentIndex: Int,
        val delta: String
    ) : LLMStreamChunk()

    /**
     * Represents a completed, opaque reasoning output item for a reasoning-capable model during streaming.
     *
     * Reasoning items carry the chain-of-thought produced by the model; the raw items (including any
     * `encrypted_content`) are opaque and are persisted so they can be replayed into a future request's
     * `input`. The item is emitted as the provider completes each reasoning output item. Its derived plaintext
     * (`summary[].text`, `content[].text`) may be rendered; the raw item and any `encrypted_content` are never
     * rendered. When persisted or replayed, the item is sanitized to the Responses `input` schema (output-only
     * fields such as `status` are stripped).
     *
     * @property reasoningItem Raw reasoning output item (e.g. `{"type":"reasoning",...}`) completed.
     */
    data class ReasoningDone(val reasoningItem: JsonObject) : LLMStreamChunk()

    /**
     * Represents the completed, authoritative function call for a streaming response.
     *
     * Unlike [ToolCallChunk] (incremental deltas for live rendering), this chunk carries the provider's
     * **final** function-call payload (e.g. via the Responses API `response.output_item.done` event), which
     * contains the authoritative `id`, `name`, and full `arguments` string. The final arguments may differ
     * from the concatenation of the streamed deltas (providers may correct the raw model output), so consumers
     * should prefer this payload over delta-accumulated arguments when present.
     *
     * This chunk is intentionally API-independent: the strategy that produced it is responsible for unpacking
     * the provider's wire format into these typed fields, so downstream consumers never touch raw JSON.
     *
     * Not every dialect emits this chunk (OpenAI Chat Completions and Ollama only stream [ToolCallChunk]
     * deltas); it is an optional authoritative override, never a replacement for delta accumulation.
     *
     * @property index The sequential index of this tool call within the streamed batch (0-based), matching
     *            [ToolCallChunk.index] semantics. This is **not** the provider's raw output-array position,
     *            which may be offset by other output items (e.g. reasoning); the producing strategy translates
     *            the provider position into this sequential index.
     * @property id The unique identifier for this tool call, if the provider assigns one.
     * @property name The name of the function to call.
     * @property arguments The full, authoritative arguments JSON string.
     */
    data class ToolCallDone(
        val index: Int?,
        val id: String?,
        val name: String,
        val arguments: String?
    ) : LLMStreamChunk()

    /**
     * Represents an observation of the usage reported so far during a stream.
     *
     * This chunk is the designated place for intermediate observations; no layer above the dialects consumes it,
     * and it is never the value that is persisted. The authoritative value of a generation is carried by
     * [FinalUsageStats].
     *
     * @property usage Usage the provider reported at this point of the stream.
     */
    data class UsageChunk(val usage: UsageStats) : LLMStreamChunk()

    /**
     * Represents the authoritative token usage of the whole generation.
     *
     * Every dialect emits this chunk in the order "final usage, then terminal chunk", and it is never a stream
     * ending itself, so a consumer sees it while the stream is still open. It is the only usage value a caller
     * persists: a stream that ends without it records no usage for its message.
     *
     * @property usage Usage the provider reported for the whole generation.
     */
    data class FinalUsageStats(val usage: UsageStats) : LLMStreamChunk()

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
