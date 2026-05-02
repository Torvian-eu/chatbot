package eu.torvian.chatbot.worker.mcp

/**
 * Normalized gateway outcome for worker-side tool invocation.
 *
 * @property isError Whether the MCP tool reported an error.
 * @property structuredContent Structured error details when [isError] is true.
 * @property textContent Primary textual content returned by the tool when successful.
 */
data class McpToolCallOutcome(
    val isError: Boolean,
    val structuredContent: String? = null,
    val textContent: String? = null
)