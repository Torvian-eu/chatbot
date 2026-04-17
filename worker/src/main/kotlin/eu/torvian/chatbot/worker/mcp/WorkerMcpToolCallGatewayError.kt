package eu.torvian.chatbot.worker.mcp

/**
 * Logical gateway errors for worker-side MCP tool invocation.
 */
sealed interface WorkerMcpToolCallGatewayError {

    /**
     * Human-readable reason describing why the gateway could not complete the tool invocation.
     *
     * @property message Error description suitable for diagnostics and result propagation.
     */
    val message: String

    /**
     * Indicates that no MCP invocation backend is available in the current worker setup.
     *
     * @property message Human-readable description of the missing backend condition.
     */
    data class NotConfigured(
        override val message: String
    ) : WorkerMcpToolCallGatewayError

    /**
     * Indicates that the target server is unknown to the invocation backend.
     *
     * @property serverId Server identifier that could not be resolved.
     * @property message Human-readable description for diagnostics.
     */
    data class UnknownServer(
        val serverId: Long,
        override val message: String
    ) : WorkerMcpToolCallGatewayError

    /**
     * Indicates that tool execution failed after invocation started.
     *
     * @property message Human-readable failure reason.
     */
    data class InvocationFailed(
        override val message: String
    ) : WorkerMcpToolCallGatewayError
}