package eu.torvian.chatbot.server.service.core.toolcall

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.SignedRequestPayloadDecodingResult
import eu.torvian.chatbot.common.security.decodePayload

/**
 * Normalized approval submissions received from the chat WebSocket client.
 *
 * This server-only model lets the chat service distinguish between Local MCP approvals, built-in
 * worker approvals that must carry detached app authorization metadata, and operator tool
 * approvals that carry a plain decision.
 */
sealed interface ToolCallApprovalSubmission {
    /** Persisted tool-call identifier that this approval refers to. */
    val toolCallId: Long

    /** Whether execution was approved by the client. */
    val approved: Boolean

    /** Optional denial reason supplied by the client. */
    val denialReason: String?

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
        /** Cached authorization metadata derived from the shared signed-payload decoder. */
        private val decodedMetadata by lazy { decodeAuthorizationMetadata(signedRequest) }

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
            /**
             * Decodes authorization metadata from the signed payload carried by one detached request.
             *
             * Attempts to extract [toolCallId], [approved], and [denialReason] from the
             * exact JSON payload that was signed by the app.
             *
             * @param signedRequest Detached signed request carrying the exact serialized authorization payload.
             * @return Decoded metadata, or a safe default if payload is malformed or incompatible.
             */
            private fun decodeAuthorizationMetadata(signedRequest: SignedRequest): AuthorizationMetadata {
                return when (val decodingResult = signedRequest.decodePayload<LocalMCPToolExecutionAuthorization>()) {
                    is SignedRequestPayloadDecodingResult.Decoded -> {
                        val auth = decodingResult.value
                        AuthorizationMetadata(
                            toolCallId = auth.toolCallId,
                            approved = auth.approved,
                            denialReason = auth.denialReason
                        )
                    }

                    // Payload could not be decoded; return a safe default that will likely not
                    // match any real tool call ID. The actual validation happens on the worker.
                    SignedRequestPayloadDecodingResult.MalformedPayload -> {
                        AuthorizationMetadata(
                            toolCallId = -1L,
                            approved = false,
                            denialReason = "Failed to decode signed authorization metadata"
                        )
                    }

                    SignedRequestPayloadDecodingResult.InvalidPayload -> {
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

    /**
     * Built-in worker tool approval carrying a detached signed request.
     *
     * Mirrors [LocalMcpSigned] for direct `tool.call` (non-MCP) tool executions. The signed
     * payload contains the exact serialized [BuiltInToolExecutionAuthorization] which the worker
     * re-verifies and uses as the single source of truth for execution.
     */
    data class BuiltInSigned(
        val signedRequest: SignedRequest
    ) : ToolCallApprovalSubmission {
        private val decodedMetadata by lazy { decodeAuthorizationMetadata(signedRequest) }

        override val toolCallId: Long
            get() = decodedMetadata.toolCallId

        override val approved: Boolean
            get() = decodedMetadata.approved

        override val denialReason: String?
            get() = decodedMetadata.denialReason

        private data class AuthorizationMetadata(
            val toolCallId: Long,
            val approved: Boolean,
            val denialReason: String?
        )

        companion object {
            private fun decodeAuthorizationMetadata(signedRequest: SignedRequest): AuthorizationMetadata {
                return when (val decodingResult = signedRequest.decodePayload<BuiltInToolExecutionAuthorization>()) {
                    is SignedRequestPayloadDecodingResult.Decoded -> {
                        val auth = decodingResult.value
                        AuthorizationMetadata(
                            toolCallId = auth.toolCallId,
                            approved = auth.approved,
                            denialReason = auth.denialReason
                        )
                    }

                    SignedRequestPayloadDecodingResult.MalformedPayload -> {
                        AuthorizationMetadata(
                            toolCallId = -1L,
                            approved = false,
                            denialReason = "Failed to decode signed built-in tool authorization metadata"
                        )
                    }

                    SignedRequestPayloadDecodingResult.InvalidPayload -> {
                        AuthorizationMetadata(
                            toolCallId = -1L,
                            approved = false,
                            denialReason = "Invalid signed built-in tool authorization metadata"
                        )
                    }
                }
            }
        }
    }

    /**
     * Operator tool approval that carries no signed request.
     *
     * Operator tools (e.g. `spawn_agent`) are executed by the operator over the chat WebSocket, so
     * there is nothing for a worker to verify — no on-device signature is produced. The approval
     * gate only needs the plain decision and an optional denial reason.
     *
     * @property toolCallId Persisted tool-call identifier this approval refers to.
     * @property approved Whether execution was approved.
     * @property denialReason Optional denial reason supplied by the user or an auto-deny preference.
     */
    data class OperatorToolApproval(
        override val toolCallId: Long,
        override val approved: Boolean,
        override val denialReason: String?,
    ) : ToolCallApprovalSubmission

    /**
     * Server built-in tool approval that carries no signed request.
     *
     * Server built-in tools (e.g. `list_agent_roles`) are executed entirely in-process on the server
     * inside the chat turn, so there is nothing for a worker to verify and no operator relay — no
     * on-device signature is produced. The approval gate only needs the plain decision and an
     * optional denial reason, mirroring [OperatorToolApproval].
     *
     * @property toolCallId Persisted tool-call identifier this approval refers to.
     * @property approved Whether execution was approved.
     * @property denialReason Optional denial reason supplied by the user or an auto-deny preference.
     */
    data class ServerBuiltInApproval(
        override val toolCallId: Long,
        override val approved: Boolean,
        override val denialReason: String?,
    ) : ToolCallApprovalSubmission
}
