package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationOptions
import eu.torvian.chatbot.worker.service.security.VerifiedSignedPayloadResult
import eu.torvian.chatbot.worker.service.security.VerificationService
import eu.torvian.chatbot.worker.service.security.verifyAndDecodeSignedPayload

/**
 * Default implementation of [LocalMCPToolExecutionAuthorizationValidator].
 *
 * Verifies the detached signature and decodes the authorization payload to serve as the
 * single source of truth for execution parameters.
 *
 * @property verificationService Worker trust-store verifier used for detached signature validation.
 * @property authorizationWindowSeconds Maximum accepted authorization age for one live execution request.
 */
class DefaultLocalMCPToolExecutionAuthorizationValidator(
    private val verificationService: VerificationService,
    private val authorizationWindowSeconds: Long = 60
) : LocalMCPToolExecutionAuthorizationValidator {
    override suspend fun validate(
        signedRequest: SignedRequest
    ): LocalMCPToolExecutionAuthorizationValidationResult {
        return when (
            val validationResult = verificationService.verifyAndDecodeSignedPayload<LocalMCPToolExecutionAuthorization>(
                signedRequest = signedRequest,
                options = VerificationOptions(
                    checkExpiration = true,
                    expirationWindowSeconds = authorizationWindowSeconds
                )
            )
        ) {
            is VerifiedSignedPayloadResult.VerificationFailed -> {
                validationResult.error.toValidationFailure(validationResult.decodedPayload?.toolCallId)
            }

            is VerifiedSignedPayloadResult.Verified -> {
                val authorization = validationResult.payload
                if (!authorization.approved) {
                    return LocalMCPToolExecutionAuthorizationValidationResult.Denied(
                        denialReason = authorization.denialReason,
                        toolCallId = authorization.toolCallId
                    )
                }

                LocalMCPToolExecutionAuthorizationValidationResult.Authorized(authorization = authorization)
            }

            VerifiedSignedPayloadResult.MalformedPayload,
            VerifiedSignedPayloadResult.InvalidPayload -> {
                LocalMCPToolExecutionAuthorizationValidationResult.MalformedSignedPayload(
                    details = "Signed payload could not be decoded as LocalMCPToolExecutionAuthorization",
                    toolCallId = null
                )
            }
        }
    }
}

/**
 * Converts worker verification failures into Local MCP authorization validation failures.
 *
 * @receiver Verification error produced by [VerificationService].
 * @param toolCallId Optional tool call identifier recovered from the signed payload for result correlation.
 * @return Structured Local MCP authorization rejection with correlated tool call ID when available.
 */
private fun VerificationError.toValidationFailure(
    toolCallId: Long? = null
): LocalMCPToolExecutionAuthorizationValidationResult.Rejected = when (this) {
    is VerificationError.UnknownSigner -> LocalMCPToolExecutionAuthorizationValidationResult.UnknownSigner(
        signerId = signerId,
        toolCallId = toolCallId
    )
    is VerificationError.InvalidSignature -> LocalMCPToolExecutionAuthorizationValidationResult.InvalidSignature(
        toolCallId = toolCallId,
        details = cause?.toString()
    )
    is VerificationError.Expired -> LocalMCPToolExecutionAuthorizationValidationResult.ExpiredAuthorization(
        timestamp = timestamp,
        ageSeconds = ageSeconds,
        toolCallId = toolCallId
    )
}