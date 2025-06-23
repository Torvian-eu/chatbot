package eu.torvian.chatbot.server.service.llm

/**
 * Represents the structured result of an LLM chat completion, independent of the specific provider API format.
 * This is the common type returned by the LLMApiClient interface.
 *
 * @property choices The list of generated completion choices. Typically contains one item when n=1 is requested.
 * @property usage Statistics about token usage for this completion request.
 * @property id An identifier for the completion request (may be provider-specific or generated).
 *              Null if the provider doesn't return a meaningful ID or it's not needed at the service level.
 * @property metadata Optional metadata from the provider. Could be used for debugging or logging.
 *                    This map should contain data that doesn't fit into the structured fields but is useful
 *                    to pass up from the specific API response.
 */
data class LLMCompletionResult(
    val choices: List<CompletionChoice>,
    val usage: UsageStats,
    val id: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
) {
    /**
     * Represents a single generated completion choice from the LLM.
     *
     * @property role The role of the message generated (e.g., "assistant", "tool").
     *                Stored as a string to be compatible with various APIs. Can be mapped to ChatMessage.Role later.
     * @property content The content of the generated message.
     * @property finishReason The reason the model stopped generating tokens for this choice.
     *                        (e.g., "stop", "length", "tool_calls"). Null if not provided by the API.
     * @property index The index of this choice in the list of choices (0 for the first).
     */
    data class CompletionChoice(
        val role: String,
        val content: String,
        val finishReason: String?,
        val index: Int
    )

    /**
     * Represents token usage statistics for the completion.
     *
     * @property promptTokens Number of tokens in the prompt/context
     * @property completionTokens Number of tokens in the generated completion
     * @property totalTokens Total tokens used (prompt + completion)
     */
    data class UsageStats(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int
    )
}
