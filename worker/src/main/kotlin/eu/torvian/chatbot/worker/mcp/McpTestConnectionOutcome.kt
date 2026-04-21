package eu.torvian.chatbot.worker.mcp

/**
 * Runtime-level test-connection outcome.
 *
 * @property discoveredToolCount Number of tools discovered during the connectivity check.
 * @property message Optional operator-facing message about the result.
 */
data class McpTestConnectionOutcome(
    val discoveredToolCount: Int,
    val message: String? = null
)