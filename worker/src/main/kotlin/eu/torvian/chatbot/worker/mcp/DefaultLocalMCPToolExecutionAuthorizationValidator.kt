package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationOptions
import eu.torvian.chatbot.worker.service.security.VerificationService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Default implementation of [LocalMCPToolExecutionAuthorizationValidator].
 *
 * Verifies the detached signature and decodes the authorization payload to serve as the
 * single source of truth for execution parameters.
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
        signedRequest: SignedRequest
    ): LocalMCPToolExecutionAuthorizationValidationResult {
        // Best-effort decode of authorization from payload for use in success and rejection paths.
        val decodedAuthorization = decodeSignedPayload(signedRequest.payload)

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