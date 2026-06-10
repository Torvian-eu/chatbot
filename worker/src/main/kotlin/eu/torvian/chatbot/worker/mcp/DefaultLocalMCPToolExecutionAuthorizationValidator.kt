package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationOptions
import eu.torvian.chatbot.worker.service.security.VerificationService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Default implementation of [LocalMCPToolExecutionAuthorizationValidator].
 *
 * @property json JSON codec used to deserialize the exact signed authorization payload string.
 * @property verificationService Worker trust-store verifier used for detached signature validation.
 * @property authorizationWindowSeconds Maximum accepted authorization age for one live execution request.
 */
class DefaultLocalMCPToolExecutionAuthorizationValidator(
    private val json: Json,
    private val verificationService: VerificationService,
    private val authorizationWindowSeconds: Long = 60
) : LocalMCPToolExecutionAuthorizationValidator {
    override suspend fun validate(
        request: LocalMCPToolCallRequest
    ): LocalMCPToolExecutionAuthorizationValidationResult {
        val signedRequest = request.signedAuthorization
            ?: return LocalMCPToolExecutionAuthorizationValidationResult.MissingSignedRequest

        return when (
            val verificationResult = verificationService.verify(
                signedRequest = signedRequest,
                options = VerificationOptions(
                    checkExpiration = true,
                    expirationWindowSeconds = authorizationWindowSeconds
                )
            )
        ) {
            is Either.Left -> verificationResult.value.toValidationFailure()
            is Either.Right -> {
                val signedAuthorization = decodeSignedPayload(signedRequest.payload)
                    ?: return LocalMCPToolExecutionAuthorizationValidationResult.MalformedSignedPayload(
                        details = "Signed payload could not be decoded as LocalMCPToolExecutionAuthorization"
                    )

                val mismatchedFields = request.findMismatchedFields(signedAuthorization)
                if (mismatchedFields.isNotEmpty()) {
                    return LocalMCPToolExecutionAuthorizationValidationResult.RequestMismatch(mismatchedFields)
                }

                if (!signedAuthorization.approved) {
                    return LocalMCPToolExecutionAuthorizationValidationResult.Denied(
                        denialReason = signedAuthorization.denialReason
                    )
                }

                LocalMCPToolExecutionAuthorizationValidationResult.Authorized
            }
        }
    }

    /**
     * Attempts to decode the exact signed payload as [LocalMCPToolExecutionAuthorization].
     *
     * @param payload Exact serialized JSON body that was signed by the app.
     * @return Decoded authorization DTO, or `null` when the payload is malformed or incompatible.
     */
    private fun decodeSignedPayload(payload: String): LocalMCPToolExecutionAuthorization? {
        return try {
            json.decodeFromString<LocalMCPToolExecutionAuthorization>(payload)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Converts worker verification failures into Local MCP authorization validation failures.
 *
 * @receiver Verification error produced by [VerificationService].
 * @return Structured Local MCP authorization rejection.
 */
private fun VerificationError.toValidationFailure(): LocalMCPToolExecutionAuthorizationValidationResult.Rejected = when (this) {
    is VerificationError.UnknownSigner -> LocalMCPToolExecutionAuthorizationValidationResult.UnknownSigner(signerId = signerId)
    is VerificationError.InvalidSignature -> LocalMCPToolExecutionAuthorizationValidationResult.InvalidSignature(
        details = cause?.toString()
    )
    is VerificationError.Expired -> LocalMCPToolExecutionAuthorizationValidationResult.ExpiredAuthorization(
        timestamp = timestamp,
        ageSeconds = ageSeconds
    )
}

/**
 * Lists Local MCP execution fields whose values differ between the relayed worker request and the signed payload.
 *
 * @receiver Worker-facing Local MCP execution request.
 * @param authorization Signed authorization payload decoded from the detached app signature.
 * @return Stable field names that callers can surface for diagnostics.
 */
private fun LocalMCPToolCallRequest.findMismatchedFields(
    authorization: LocalMCPToolExecutionAuthorization
): List<String> {
    val mismatches = mutableListOf<String>()
    if (toolCallId != authorization.toolCallId) mismatches += "toolCallId"
    if (sessionId != authorization.sessionId) mismatches += "sessionId"
    if (messageId != authorization.messageId) mismatches += "messageId"
    if (toolDefinitionId != authorization.toolDefinitionId) mismatches += "toolDefinitionId"
    if (toolName != authorization.toolName) mismatches += "toolName"
    if (serverId != authorization.serverId) mismatches += "serverId"
    if (mcpToolName != authorization.mcpToolName) mismatches += "mcpToolName"
    if (inputJson != authorization.input) mismatches += "input"
    if (approved != authorization.approved) mismatches += "approved"
    if (denialReason != authorization.denialReason) mismatches += "denialReason"
    return mismatches
}