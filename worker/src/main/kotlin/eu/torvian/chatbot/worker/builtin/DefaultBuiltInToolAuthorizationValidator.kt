package eu.torvian.chatbot.worker.builtin

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationOptions
import eu.torvian.chatbot.worker.service.security.VerificationService
import eu.torvian.chatbot.worker.service.security.VerifiedSignedPayloadResult
import eu.torvian.chatbot.worker.service.security.verifyAndDecodeSignedPayload

/**
 * Default implementation of [BuiltInToolAuthorizationValidator] backed by the worker's
 * [VerificationService].
 *
 * @property verificationService Worker trust-store verifier used for detached signature validation.
 * @property authorizationWindowSeconds Maximum accepted authorization age in seconds.
 */
class DefaultBuiltInToolAuthorizationValidator(
    private val verificationService: VerificationService,
    private val authorizationWindowSeconds: Long = 60,
) : BuiltInToolAuthorizationValidator {
    override suspend fun validate(
        signedRequest: SignedRequest
    ): BuiltInToolAuthorizationValidationResult {
        return when (
            val validationResult = verificationService.verifyAndDecodeSignedPayload<BuiltInToolExecutionAuthorization>(
                signedRequest = signedRequest,
                options = VerificationOptions(
                    checkExpiration = true,
                    expirationWindowSeconds = authorizationWindowSeconds,
                ),
            )
        ) {
            is VerifiedSignedPayloadResult.Verified -> {
                val authorization = validationResult.payload
                if (!authorization.approved) {
                    return BuiltInToolAuthorizationValidationResult.Denied(
                        message = authorization.denialReason ?: "User denied tool execution",
                        denialReason = authorization.denialReason,
                        toolCallId = authorization.toolCallId,
                    )
                }
                BuiltInToolAuthorizationValidationResult.Authorized(authorization = authorization)
            }

            is VerifiedSignedPayloadResult.VerificationFailed -> {
                validationResult.error.toValidationFailure(validationResult.decodedPayload?.toolCallId)
            }

            VerifiedSignedPayloadResult.MalformedPayload,
            VerifiedSignedPayloadResult.InvalidPayload -> {
                BuiltInToolAuthorizationValidationResult.MalformedPayload(
                    message = "Signed payload could not be decoded as BuiltInToolExecutionAuthorization",
                )
            }
        }
    }
}

/**
 * Converts worker verification failures into built-in authorization validation failures.
 */
private fun VerificationError.toValidationFailure(
    toolCallId: Long? = null,
): BuiltInToolAuthorizationValidationResult.Rejected = when (this) {
    is VerificationError.UnknownSigner -> BuiltInToolAuthorizationValidationResult.UnknownSigner(
        message = "Unknown signer: $signerId",
        signerId = signerId,
        toolCallId = toolCallId,
    )
    is VerificationError.InvalidSignature -> BuiltInToolAuthorizationValidationResult.InvalidSignature(
        message = "Signature verification failed: ${cause?.toString() ?: "no detail"}",
        toolCallId = toolCallId,
    )
    is VerificationError.Expired -> BuiltInToolAuthorizationValidationResult.Expired(
        message = "Authorization expired (age=$ageSeconds s)",
        timestamp = timestamp,
        ageSeconds = ageSeconds,
        toolCallId = toolCallId,
    )
}

