package eu.torvian.chatbot.server.service.llm.strategy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data Transfer Objects for OpenAI's Responses API.
 *
 * These models represent the subset of the Responses API payloads needed by [ResponsesStrategy]:
 * the request (built dynamically), the non-streaming response, and the semantic streaming events.
 */
object ResponsesApiModels {

    /**
     * The Responses API non-streaming response payload.
     *
     * @property id Unique identifier for the response (`resp_...`).
     * @property objectType Always "response".
     * @property status Lifecycle status ("in_progress", "completed", "failed", ...).
     * @property model The model that generated the response.
     * @property output Ordered output items produced by the model.
     * @property reasoning Top-level reasoning configuration echoed back.
     * @property usage Token usage statistics.
     * @property previousResponseId Id of the previous response used for chaining.
     */
    @Serializable
    data class ResponsesResponse(
        val id: String? = null,
        @SerialName("object") val objectType: String? = null,
        val status: String? = null,
        val model: String? = null,
        val output: List<ResponseOutputItem> = emptyList(),
        val reasoning: Reasoning? = null,
        val usage: Usage? = null,
        @SerialName("previous_response_id") val previousResponseId: String? = null
    )

    /**
     * A typed output item from the Responses `output` array.
     *
     * @property type The item type ("message", "function_call", "reasoning", "function_call_output", ...).
     * @property role Author role for message items.
     * @property content Content parts for message items.
     * @property callId The `call_id` for function_call / function_call_output items.
     * @property name Tool name for function_call / function_call_output items.
     * @property arguments JSON-string arguments for function_call items.
     * @property summary Reasoning summary content for reasoning items.
     */
    @Serializable
    data class ResponseOutputItem(
        val type: String? = null,
        val role: String? = null,
        val content: List<ContentPart>? = null,
        @SerialName("call_id") val callId: String? = null,
        val name: String? = null,
        val arguments: String? = null,
        val summary: List<SummaryTextContent>? = null
    )

    /**
     * A content part within a message output item.
     *
     * @property type Content part type ("output_text", "input_text", ...).
     * @property text The text content for text parts.
     */
    @Serializable
    data class ContentPart(
        val type: String? = null,
        val text: String? = null
    )

    /**
     * Reasoning summary content within a reasoning item.
     *
     * @property text The summarized reasoning text.
     */
    @Serializable
    data class SummaryTextContent(
        val text: String? = null
    )

    /**
     * Top-level reasoning metadata echoed back in the response.
     *
     * @property effort The reasoning effort used.
     * @property summary Reasoning summary metadata.
     */
    @Serializable
    data class Reasoning(
        val effort: String? = null,
        val summary: String? = null
    )

    /**
     * Token usage statistics returned by the Responses API.
     *
     * @property inputTokens Tokens in the input context.
     * @property outputTokens Tokens in the generated output.
     * @property totalTokens Total tokens used.
     * @property outputTokensDetails Detailed output token breakdown including reasoning tokens.
     */
    @Serializable
    data class Usage(
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
        @SerialName("total_tokens") val totalTokens: Int? = null,
        @SerialName("output_tokens_details") val outputTokensDetails: OutputTokensDetails? = null
    )

    /**
     * Detailed output token breakdown.
     *
     * @property reasoningTokens Number of reasoning (chain-of-thought) tokens.
     */
    @Serializable
    data class OutputTokensDetails(
        @SerialName("reasoning_tokens") val reasoningTokens: Int? = null
    )

    /**
     * A semantic streaming event emitted by the Responses API over SSE.
     *
     * Only a subset of event fields is decoded; unknown event types are simply ignored.
     *
     * @property type The event type (e.g. "response.output_text.delta", "response.completed", "error").
     * @property delta Timed event delta content (used for text and argument deltas).
     * @property callId The `call_id` of the function call for function-call argument deltas.
     * @property name Tool name for function-call deltas.
     * @property response Embedded response snapshot carried by lifecycle events such as `response.completed`.
     * @property message Error message for `error` events.
     */
    @Serializable
    data class StreamEvent(
        val type: String? = null,
        @SerialName("delta") val delta: String? = null,
        @SerialName("call_id") val callId: String? = null,
        val name: String? = null,
        val response: ResponsesResponse? = null,
        val message: String? = null
    )

    /**
     * OpenAI-compatible error body wrapper, shared by Chat Completions and Responses endpoints.
     *
     * @property error Structured error details.
     */
    @Serializable
    data class OpenAiErrorResponse(@SerialName("error") val error: OpenAiErrorDetail) {
        /**
         * Detailed error information.
         *
         * @property message Human-readable error message.
         */
        @Serializable
        data class OpenAiErrorDetail(val message: String)
    }
}