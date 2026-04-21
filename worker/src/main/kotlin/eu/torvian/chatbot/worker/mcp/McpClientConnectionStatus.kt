package eu.torvian.chatbot.worker.mcp

import kotlin.time.Instant

/**
 * Connection metadata snapshot for one worker-managed MCP client.
 *
 * @property connectedAt Timestamp when the SDK client connection was established.
 * @property lastActivityAt Timestamp of the latest observed client activity.
 */
data class McpClientConnectionStatus(
    val connectedAt: Instant,
    val lastActivityAt: Instant
)