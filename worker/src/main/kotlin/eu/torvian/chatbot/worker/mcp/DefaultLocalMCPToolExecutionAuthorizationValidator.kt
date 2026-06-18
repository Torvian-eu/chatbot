package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequestPayloadDecodingResult
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.decodePayload
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationOptions
import eu.torvian.chatbot.worker.service.security.VerificationService

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
        // Best-effort decode of authorization from payload for use in success and rejection paths.
        val decodedAuthorization = when (
            val decodingResult = signedRequest.decodePayload<LocalMCPToolExecutionAuthorization>()
        ) {
            is SignedRequestPayloadDecodingResult.Decoded -> decodingResult.value
            SignedRequestPayloadDecodingResult.MalformedPayload,
            SignedRequestPayloadDecodingResult.InvalidPayload -> null
        }

        return when (
            val verificationResult = verificationService.verify(
                signedRequest = signedRequest,
                options = VerificationOptions(
                    checkExpiration = true,
                    expirationWindowSeconds = authorizationWindowSeconds
                )
            )
        ) {
            is Either.Left -> verificationResult.value.toValidationFailure(decodedAuthorization?.toolCallId)
            is Either.Right -> {
                val authorization = decodedAuthorization
                    ?: return LocalMCPToolExecutionAuthorizationValidationResult.MalformedSignedPayload(
                        details = "Signed payload could not be decoded as LocalMCPToolExecutionAuthorization",
                        toolCallId = null
                    )

                if (!authorization.approved) {
                    return LocalMCPToolExecutionAuthorizationValidationResult.Denied(
                        denialReason = authorization.denialReason,
                        toolCallId = authorization.toolCallId
                    )
                }

                LocalMCPToolExecutionAuthorizationValidationResult.Authorized(authorization = authorization)
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