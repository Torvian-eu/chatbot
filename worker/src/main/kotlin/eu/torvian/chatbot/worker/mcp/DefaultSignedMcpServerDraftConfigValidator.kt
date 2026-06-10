package eu.torvian.chatbot.worker.mcp

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerDraftConnectionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestDraftConnectionCommandData
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Default implementation of [SignedMcpServerDraftConfigValidator].
 *
 * @property json JSON codec used to deserialize the exact signed payload string without rewriting it.
 * @property verificationService Worker trust-store verifier used for detached signature validation.
 */
class DefaultSignedMcpServerDraftConfigValidator(
    private val json: Json,
    private val verificationService: VerificationService
) : SignedMcpServerDraftConfigValidator {

    override suspend fun validate(
        request: WorkerMcpServerTestDraftConnectionCommandData
    ): SignedMcpServerDraftConfigValidationResult {
        val signedRequest = request.signedRequest
            ?: return SignedMcpServerDraftConfigValidationResult.MissingSignedRequest

        return when (
            val verificationResult = verificationService.verify(signedRequest = signedRequest)
        ) {
            is Either.Left -> verificationResult.value.toValidationFailure()
            is Either.Right -> {
                val signedPayload = decodeSignedPayload(signedRequest)
                    ?: return SignedMcpServerDraftConfigValidationResult.MalformedSignedPayload(
                        details = "Signed payload could not be decoded as TestLocalMCPServerDraftConnectionRequest"
                    )

                val mismatchedFields = request.findMismatchedFields(signedPayload)
                if (mismatchedFields.isEmpty()) {
                    SignedMcpServerDraftConfigValidationResult.Authorized
                } else {
                    SignedMcpServerDraftConfigValidationResult.DtoMismatch(mismatchedFields)
                }
            }
        }
    }

    /**
     * Deserializes the exact signed payload as a [TestLocalMCPServerDraftConnectionRequest].
     *
     * @param signedRequest Detached signed request carrying the exact serialized payload string.
     * @return Decoded draft request, or `null` when the payload is malformed or not a draft request.
     */
    private fun decodeSignedPayload(
        signedRequest: SignedRequest
    ): TestLocalMCPServerDraftConnectionRequest? {
        return try {
            json.decodeFromString(
                TestLocalMCPServerDraftConnectionRequest.serializer(),
                signedRequest.payload
            )
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Converts verification failures into the MCP draft config authorization failure model.
 *
 * @receiver Verification error produced by [VerificationService].
 * @return Structured draft validation failure.
 */
private fun VerificationError.toValidationFailure(): SignedMcpServerDraftConfigValidationResult.Rejected = when (this) {
    is VerificationError.UnknownSigner -> SignedMcpServerDraftConfigValidationResult.UnknownSigner(signerId = signerId)
    is VerificationError.InvalidSignature -> SignedMcpServerDraftConfigValidationResult.InvalidSignature(
        details = cause?.toString()
    )
    is VerificationError.Expired -> SignedMcpServerDraftConfigValidationResult.ExpiredSignedRequest(
        details = "Expired signed request for timestamp=$timestamp ageSeconds=$ageSeconds"
    )
}

/**
 * Lists the request-derived fields whose values do not match the relayed draft test DTO.
 *
 * @receiver Relayed draft test command data about to be trusted by the worker.
 * @param signedPayload Normalized draft request decoded from the trusted signed payload.
 * @return Ordered list of mismatched field names.
 */
private fun WorkerMcpServerTestDraftConnectionCommandData.findMismatchedFields(
    signedPayload: TestLocalMCPServerDraftConnectionRequest
): List<String> = buildList {
    if (workerId != signedPayload.workerId) add("workerId")
    if (name != signedPayload.name) add("name")
    if (command != signedPayload.command) add("command")
    if (arguments != signedPayload.arguments) add("arguments")
    if (workingDirectory != signedPayload.workingDirectory) add("workingDirectory")
    if (environmentVariables != signedPayload.environmentVariables) add("environmentVariables")
    if (secretEnvironmentVariables != signedPayload.secretEnvironmentVariables) add("secretEnvironmentVariables")
}
