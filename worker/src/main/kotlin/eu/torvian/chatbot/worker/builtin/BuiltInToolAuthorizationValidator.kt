package eu.torvian.chatbot.worker.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest

/**
 * Validation outcome of a single built-in tool execution authorization.
 */
sealed interface BuiltInToolAuthorizationValidationResult {
    /**
     * Authorization verified and approved.
     *
     * @property authorization Decoded authorization payload.
     */
    data class Authorized(val authorization: BuiltInToolExecutionAuthorization) : BuiltInToolAuthorizationValidationResult

    /**
     * Authorization was rejected by the verifier or the payload is malformed.
     */
    sealed interface Rejected : BuiltInToolAuthorizationValidationResult {
        /** Stable machine-readable error code. */
        val code: String
        /** Human-readable rejection message. */
        val message: String
        /** Best-effort recovered tool call id for result correlation. */
        val toolCallId: Long?
    }

    data class UnknownSigner(
        override val code: String = "unknown_signer",
        override val message: String,
        val signerId: String,
        override val toolCallId: Long? = null,
    ) : Rejected

    data class InvalidSignature(
        override val code: String = "invalid_signature",
        override val message: String,
        override val toolCallId: Long? = null,
    ) : Rejected

    data class Expired(
        override val code: String = "expired",
        override val message: String,
        val timestamp: Long,
        val ageSeconds: Long,
        override val toolCallId: Long? = null,
    ) : Rejected

    data class Denied(
        override val code: String = "denied",
        override val message: String,
        val denialReason: String?,
        override val toolCallId: Long? = null,
    ) : Rejected

    data class MalformedPayload(
        override val code: String = "malformed_payload",
        override val message: String,
        override val toolCallId: Long? = null,
    ) : Rejected
}

/**
 * Validates a detached signed request and decodes it into a [BuiltInToolExecutionAuthorization].
 *
 * Mirrors the Local MCP authorization validator so the same trust store and pipeline can be used
 * for both MCP and direct built-in tools.
 */
interface BuiltInToolAuthorizationValidator {
    /**
     * @param signedRequest Detached signed request carrying the built-in authorization payload.
     * @return Validation outcome that is one of [BuiltInToolAuthorizationValidationResult.Authorized]
     *   or one of the `Rejected` variants.
     */
    suspend fun validate(signedRequest: SignedRequest): BuiltInToolAuthorizationValidationResult
}

