package eu.torvian.chatbot.worker.mcp

/**
 * Structured outcome of worker-side Local MCP execution authorization validation.
 */
sealed interface LocalMCPToolExecutionAuthorizationValidationResult {
    /** Indicates that the worker may trust and execute the relayed Local MCP tool call. */
    data object Authorized : LocalMCPToolExecutionAuthorizationValidationResult

    /**
     * Base contract for Local MCP authorization rejections that should remain visible in worker logs and
     * in the structured tool-call result returned to the server.
     */
    sealed interface Rejected : LocalMCPToolExecutionAuthorizationValidationResult {
        /** Stable machine-readable rejection code. */
        val code: String

        /** Human-readable rejection summary. */
        val message: String

        /** Optional extra diagnostics suitable for logs or server-facing error payloads. */
        val details: String?
    }

    /** Indicates that the worker request omitted the detached app authorization entirely. */
    data object MissingSignedRequest : Rejected {
        override val code: String = "LOCAL_MCP_AUTH_MISSING_SIGNED_REQUEST"
        override val message: String = "Missing detached app authorization for Local MCP tool execution"
        override val details: String? = null
    }

    /**
     * Indicates that the detached authorization signature could not be verified.
     *
     * @property details Additional verification diagnostics, when available.
     */
    data class InvalidSignature(
        override val details: String? = null
    ) : Rejected {
        override val code: String = "LOCAL_MCP_AUTH_INVALID_SIGNATURE"
        override val message: String = "Detached Local MCP authorization signature verification failed"
    }

    /**
     * Indicates that the detached authorization references a signer absent from the worker trust store.
     *
     * @property signerId Untrusted signer identifier presented by the rejected signed request.
     */
    data class UnknownSigner(
        val signerId: String
    ) : Rejected {
        override val code: String = "LOCAL_MCP_AUTH_UNKNOWN_SIGNER"
        override val message: String = "Detached Local MCP authorization used an unknown signer"
        override val details: String = "signerId=$signerId"
    }

    /**
     * Indicates that the detached authorization is no longer fresh enough for live execution.
     *
     * @property timestamp Authorization timestamp in epoch milliseconds.
     * @property ageSeconds Signed age relative to the worker clock in seconds.
     */
    data class ExpiredAuthorization(
        val timestamp: Long,
        val ageSeconds: Long
    ) : Rejected {
        override val code: String = "LOCAL_MCP_AUTH_EXPIRED"
        override val message: String = "Detached Local MCP authorization has expired"
        override val details: String = "timestamp=$timestamp ageSeconds=$ageSeconds"
    }

    /**
     * Indicates that the signed payload could not be decoded as a Local MCP execution authorization DTO.
     *
     * @property details Additional payload-decoding diagnostics, when available.
     */
    data class MalformedSignedPayload(
        override val details: String? = null
    ) : Rejected {
        override val code: String = "LOCAL_MCP_AUTH_MALFORMED_SIGNED_PAYLOAD"
        override val message: String = "Detached Local MCP authorization payload is malformed"
    }

    /**
     * Indicates that the signed authorization payload does not match the execution command relayed to the worker.
     *
     * @property mismatchedFields Explicit field names whose values differ between the signed payload and request.
     */
    data class RequestMismatch(
        val mismatchedFields: List<String>
    ) : Rejected {
        override val code: String = "LOCAL_MCP_AUTH_REQUEST_MISMATCH"
        override val message: String = "Detached Local MCP authorization does not match the relayed tool execution request"
        override val details: String = "mismatchedFields=${mismatchedFields.joinToString(",")}"
    }

    /**
     * Indicates that the signed authorization explicitly denied the execution request.
     *
     * @property denialReason Optional denial reason carried by the signed app authorization.
     */
    data class Denied(
        val denialReason: String?
    ) : Rejected {
        override val code: String = "LOCAL_MCP_AUTH_DENIED"
        override val message: String = "Detached Local MCP authorization denied tool execution"
        override val details: String? = denialReason?.let { "denialReason=$it" }
    }
}