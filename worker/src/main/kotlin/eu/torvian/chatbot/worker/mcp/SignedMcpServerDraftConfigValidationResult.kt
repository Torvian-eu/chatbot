package eu.torvian.chatbot.worker.mcp

/**
 * Structured outcome of worker-side signed draft MCP server config authorization.
 */
sealed interface SignedMcpServerDraftConfigValidationResult {
    /**
     * Indicates that the worker may trust and execute the transient draft test.
     */
    data object Authorized : SignedMcpServerDraftConfigValidationResult

    /**
     * Base contract for authorization failures that should be logged or returned to the server.
     */
    sealed interface Rejected : SignedMcpServerDraftConfigValidationResult {
        /** Stable machine-readable rejection code. */
        val code: String

        /** Human-readable rejection summary. */
        val message: String

        /** Optional extra diagnostics suitable for logs or server-facing error payloads. */
        val details: String?
    }

    /**
     * Indicates that the draft test request did not include detached signed-request metadata.
     */
    data object MissingSignedRequest : Rejected {
        override val code: String = "MCP_DRAFT_MISSING_SIGNED_REQUEST"
        override val message: String = "Missing detached signed request for MCP draft server configuration"
        override val details: String? = null
    }

    /**
     * Indicates that the detached signature could not be verified against a trusted signer key.
     *
     * @property details Additional verification diagnostics, when available.
     */
    data class InvalidSignature(
        override val details: String? = null
    ) : Rejected {
        override val code: String = "MCP_DRAFT_INVALID_SIGNATURE"
        override val message: String = "Detached MCP draft configuration signature verification failed"
    }

    /**
     * Indicates that the detached signature references a signer absent from the worker trust store.
     *
     * @property signerId Untrusted signer identifier presented by the rejected signed request.
     */
    data class UnknownSigner(
        val signerId: String
    ) : Rejected {
        override val code: String = "MCP_DRAFT_UNKNOWN_SIGNER"
        override val message: String = "Detached MCP draft configuration signature used an unknown signer"
        override val details: String = "signerId=$signerId"
    }

    /**
     * Indicates that the signed request timestamp is outside the accepted transient-command window.
     *
     * @property details Additional verification diagnostics, when available.
     */
    data class ExpiredSignedRequest(
        override val details: String? = null
    ) : Rejected {
        override val code: String = "MCP_DRAFT_EXPIRED_SIGNED_REQUEST"
        override val message: String = "Detached signed request for MCP draft configuration has expired"
    }

    /**
     * Indicates that the signed payload is not a valid draft connection request body.
     *
     * @property details Additional payload-decoding diagnostics, when available.
     */
    data class MalformedSignedPayload(
        override val details: String? = null
    ) : Rejected {
        override val code: String = "MCP_DRAFT_MALFORMED_SIGNED_PAYLOAD"
        override val message: String = "Detached MCP draft configuration payload is not a valid draft request"
    }

    /**
     * Indicates that the signed payload and relayed draft request disagree on one or more fields.
     *
     * @property mismatchedFields Explicit request-derived field names whose values differ.
     */
    data class DtoMismatch(
        val mismatchedFields: List<String>
    ) : Rejected {
        override val code: String = "MCP_DRAFT_DTO_MISMATCH"
        override val message: String = "Detached MCP draft configuration payload does not match relayed request"
        override val details: String = "mismatchedFields=${mismatchedFields.joinToString(",")}"
    }
}
