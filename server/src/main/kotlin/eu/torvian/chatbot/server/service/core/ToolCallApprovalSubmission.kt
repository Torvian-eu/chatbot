package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
import eu.torvian.chatbot.common.security.SignedRequest

/**
 * Normalized approval submissions received from the chat WebSocket client.
 *
 * This server-only model lets the chat service distinguish between regular tool approvals and
 * Local MCP approvals that must carry detached app authorization metadata.
 */
sealed interface ToolCallApprovalSubmission {
    /** Persisted tool-call identifier that this approval refers to. */
    val toolCallId: Long

    /** Whether execution was approved by the client. */
    val approved: Boolean

    /** Optional denial reason supplied by the client. */
    val denialReason: String?

    /**
     * Plain approval response used for non-Local-MCP tools.
     *
     * @property response Original client response.
     */
    data class Standard(
        val response: ToolCallApprovalResponse
    ) : ToolCallApprovalSubmission {
        override val toolCallId: Long = response.toolCallId
        override val approved: Boolean = response.approved
        override val denialReason: String? = response.denialReason
    }

    /**
     * Local MCP approval carrying the exact signed authorization payload and detached signature.
     *
     * @property authorization Exact authorization payload that the app signed.
     * @property signedRequest Detached signature metadata for [authorization].
     */
    data class LocalMcpSigned(
        val authorization: LocalMCPToolExecutionAuthorization,
        val signedRequest: SignedRequest
    ) : ToolCallApprovalSubmission {
        override val toolCallId: Long = authorization.toolCallId
        override val approved: Boolean = authorization.approved
        override val denialReason: String? = authorization.denialReason
    }
}