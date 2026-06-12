package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.tool.ToolCallApprovalResponse
import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

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
     * Local MCP approval carrying only the detached signed request.
     *
     * The signed [signedRequest].payload contains the exact serialized [LocalMCPToolExecutionAuthorization],
     * which is relayed to the worker for verification and decoding.
     *
     * The server derives [toolCallId], [approved], and [denialReason] from the signed payload
     * for internal flow control (e.g., matching approvals to pending tool calls), but does not
     * use these derived values for worker execution—the worker always decodes the signature.
     *
     * @property signedRequest Detached signature metadata and the exact Local MCP execution authorization payload.
     */
    data class LocalMcpSigned(
        val signedRequest: SignedRequest
    ) : ToolCallApprovalSubmission {
        private val decodedMetadata by lazy { decodeAuthorizationMetadata(signedRequest.payload) }

        override val toolCallId: Long
            get() = decodedMetadata.toolCallId

        override val approved: Boolean
            get() = decodedMetadata.approved

        override val denialReason: String?
            get() = decodedMetadata.denialReason

        /**
         * Decoding result for authorization metadata extracted from the signed payload.
         *
         * @property toolCallId Tool call identifier from the signed payload.
         * @property approved Whether the signed authorization approves execution.
         * @property denialReason Optional denial reason from the signed payload.
         */
        private data class AuthorizationMetadata(
            val toolCallId: Long,
            val approved: Boolean,
            val denialReason: String?
        )

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            /**
             * Decodes authorization metadata from the signed payload string.
             *
             * Attempts to extract [toolCallId], [approved], and [denialReason] from the
             * exact JSON payload that was signed by the app.
             *
             * @param payload Exact serialized authorization JSON string.
             * @return Decoded metadata, or a safe default if payload is malformed.
             */
            private fun decodeAuthorizationMetadata(payload: String): AuthorizationMetadata {
                return try {
                    val auth = json.decodeFromString<LocalMCPToolExecutionAuthorization>(payload)
                    AuthorizationMetadata(
                        toolCallId = auth.toolCallId,
                        approved = auth.approved,
                        denialReason = auth.denialReason
                    )
                } catch (_: SerializationException) {
                    // Payload could not be decoded; return a safe default that will likely
                    // not match any real tool call ID. The actual validation happens on the worker.
                    AuthorizationMetadata(
                        toolCallId = -1L,
                        approved = false,
                        denialReason = "Failed to decode signed authorization metadata"
                    )
                } catch (_: IllegalArgumentException) {
                    AuthorizationMetadata(
                        toolCallId = -1L,
                        approved = false,
                        denialReason = "Invalid signed authorization metadata"
                    )
                }
            }
        }
    }
}