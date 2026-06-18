package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerDraftConnectionRequest
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerMcpServerTestDraftConnectionCommandData
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerifiedSignedPayloadResult
import eu.torvian.chatbot.worker.service.security.VerificationService
import eu.torvian.chatbot.worker.service.security.verifyAndDecodeSignedPayload

/**
 * Default implementation of [SignedMcpServerDraftConfigValidator].
 *
 * @property verificationService Worker trust-store verifier used for detached signature validation.
 */
class DefaultSignedMcpServerDraftConfigValidator(
    private val verificationService: VerificationService
) : SignedMcpServerDraftConfigValidator {

    override suspend fun validate(
        request: WorkerMcpServerTestDraftConnectionCommandData
    ): SignedMcpServerDraftConfigValidationResult {
        return when (
            val validationResult = verificationService.verifyAndDecodeSignedPayload<TestLocalMCPServerDraftConnectionRequest>(
                signedRequest = request.signedRequest
            )
        ) {
            null -> SignedMcpServerDraftConfigValidationResult.MissingSignedRequest

            is VerifiedSignedPayloadResult.VerificationFailed -> validationResult.error.toValidationFailure()

            is VerifiedSignedPayloadResult.Verified -> {
                val mismatchedFields = request.findMismatchedFields(validationResult.payload)
                if (mismatchedFields.isEmpty()) {
                    SignedMcpServerDraftConfigValidationResult.Authorized
                } else {
                    SignedMcpServerDraftConfigValidationResult.DtoMismatch(mismatchedFields)
                }
            }

            VerifiedSignedPayloadResult.MalformedPayload,
            VerifiedSignedPayloadResult.InvalidPayload -> {
                SignedMcpServerDraftConfigValidationResult.MalformedSignedPayload(
                    details = "Signed payload could not be decoded as TestLocalMCPServerDraftConnectionRequest"
                )
            }
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
