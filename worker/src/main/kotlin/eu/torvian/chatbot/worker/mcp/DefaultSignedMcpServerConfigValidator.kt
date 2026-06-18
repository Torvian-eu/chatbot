package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.SignedRequestPayloadDecodingResult
import eu.torvian.chatbot.common.security.decodePayloadOrNull
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationOptions
import eu.torvian.chatbot.worker.service.security.VerifiedSignedPayloadResult
import eu.torvian.chatbot.worker.service.security.VerificationService
import eu.torvian.chatbot.worker.service.security.verifyAndDecodeSignedPayload

/**
 * Default implementation of [SignedMcpServerConfigValidator].
 *
 * @property verificationService Worker trust-store verifier used for detached signature validation.
 */
class DefaultSignedMcpServerConfigValidator(
    private val verificationService: VerificationService
) : SignedMcpServerConfigValidator {
    override suspend fun validate(
        server: LocalMCPServerDto,
        signedRequest: SignedRequest?
    ): SignedMcpServerConfigValidationResult {
        return when (
            val validationResult = verificationService.verifyAndDecodeSignedPayload(
                signedRequest = signedRequest,
                options = VerificationOptions(checkExpiration = false),
                decodePayload = ::decodeSignedPayload
            )
        ) {
            null -> SignedMcpServerConfigValidationResult.MissingSignedRequest

            is VerifiedSignedPayloadResult.VerificationFailed -> validationResult.error.toValidationFailure()

            is VerifiedSignedPayloadResult.Verified -> {
                val mismatchedFields = server.findMismatchedFields(validationResult.payload)
                if (mismatchedFields.isEmpty()) {
                    SignedMcpServerConfigValidationResult.Authorized
                } else {
                    SignedMcpServerConfigValidationResult.DtoMismatch(mismatchedFields)
                }
            }

            VerifiedSignedPayloadResult.MalformedPayload,
            VerifiedSignedPayloadResult.InvalidPayload -> {
                SignedMcpServerConfigValidationResult.MalformedSignedPayload(
                    details = "Signed payload could not be decoded as either CreateLocalMCPServerRequest or UpdateLocalMCPServerRequest"
                )
            }
        }
    }

    /**
     * Deserializes the exact signed payload as either supported MCP server mutation request type.
     *
     * Bootstrap snapshots may originate from either create or update operations, so the worker accepts
     * both request shapes and compares only the request-derived fields shared with the persisted DTO.
     *
     * @param signedRequest Detached signed request carrying the exact serialized payload string.
     * @return Structured decoding result for normalized request-derived fields.
     */
    private fun decodeSignedPayload(
        signedRequest: SignedRequest
    ): SignedRequestPayloadDecodingResult<NormalizedSignedMcpServerPayload> {
        decodeCreatePayload(signedRequest)?.let { payload ->
            return SignedRequestPayloadDecodingResult.Decoded(payload)
        }
        decodeUpdatePayload(signedRequest)?.let { payload ->
            return SignedRequestPayloadDecodingResult.Decoded(payload)
        }

        // The current validator intentionally collapses unsupported create/update payload shapes into
        // the existing malformed-payload rejection model.
        return SignedRequestPayloadDecodingResult.MalformedPayload
    }

    /**
     * Attempts to decode the signed payload as a create request.
     *
     * @param signedRequest Detached signed request whose payload should match a create request.
     * @return Normalized fields when decoding succeeds, or `null` on unsupported payload shape.
     */
    private fun decodeCreatePayload(signedRequest: SignedRequest): NormalizedSignedMcpServerPayload? =
        signedRequest.decodePayloadOrNull<CreateLocalMCPServerRequest>()?.toNormalizedSignedPayload()

    /**
     * Attempts to decode the signed payload as an update request.
     *
     * @param signedRequest Detached signed request whose payload should match an update request.
     * @return Normalized fields when decoding succeeds, or `null` on unsupported payload shape.
     */
    private fun decodeUpdatePayload(signedRequest: SignedRequest): NormalizedSignedMcpServerPayload? =
        signedRequest.decodePayloadOrNull<UpdateLocalMCPServerRequest>()?.toNormalizedSignedPayload()
}

/**
 * Normalized request-derived MCP server fields used for DTO comparison.
 *
 * Server-generated fields such as IDs and timestamps are intentionally excluded so the worker only
 * compares the executable or configuration state that was actually authorized by the signer.
 *
 * @property workerId Worker assignment authorized by the signer.
 * @property name User-facing server name.
 * @property description Optional description text.
 * @property command Executable command.
 * @property arguments Executable argument list.
 * @property workingDirectory Optional working directory.
 * @property isEnabled Whether the config is enabled.
 * @property autoStartOnEnable Whether enabling should auto-start the runtime.
 * @property autoStartOnLaunch Whether worker launch should auto-start the runtime.
 * @property autoStopAfterInactivitySeconds Optional inactivity timeout.
 * @property toolNamePrefix Optional discovered-tool prefix.
 * @property environmentVariables Non-secret environment variables.
 * @property secretEnvironmentVariables Secret environment variables in plaintext.
 */
private data class NormalizedSignedMcpServerPayload(
    val workerId: Long,
    val name: String,
    val description: String?,
    val command: String,
    val arguments: List<String>,
    val workingDirectory: String?,
    val isEnabled: Boolean,
    val autoStartOnEnable: Boolean,
    val autoStartOnLaunch: Boolean,
    val autoStopAfterInactivitySeconds: Int?,
    val toolNamePrefix: String?,
    val environmentVariables: List<LocalMCPEnvironmentVariableDto>,
    val secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto>
)

/**
 * Converts verification failures into the MCP config authorization failure model.
 *
 * @receiver Verification error produced by [VerificationService].
 * @return Structured MCP config validation failure.
 */
private fun VerificationError.toValidationFailure(): SignedMcpServerConfigValidationResult.Rejected = when (this) {
    is VerificationError.UnknownSigner -> SignedMcpServerConfigValidationResult.UnknownSigner(signerId = signerId)
    is VerificationError.InvalidSignature -> SignedMcpServerConfigValidationResult.InvalidSignature(
        details = cause?.toString()
    )
    is VerificationError.Expired -> SignedMcpServerConfigValidationResult.InvalidSignature(
        details = "Unexpected expiration rejection for timestamp=$timestamp ageSeconds=$ageSeconds while expiration checks were disabled"
    )
}

/**
 * Normalizes a create request into the shared comparison shape.
 *
 * @receiver Create request decoded from the signed payload.
 * @return Request-derived fields used for DTO comparison.
 */
private fun CreateLocalMCPServerRequest.toNormalizedSignedPayload(): NormalizedSignedMcpServerPayload =
    NormalizedSignedMcpServerPayload(
        workerId = workerId,
        name = name,
        description = description,
        command = command,
        arguments = arguments,
        workingDirectory = workingDirectory,
        isEnabled = isEnabled,
        autoStartOnEnable = autoStartOnEnable,
        autoStartOnLaunch = autoStartOnLaunch,
        autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
        toolNamePrefix = toolNamePrefix,
        environmentVariables = environmentVariables,
        secretEnvironmentVariables = secretEnvironmentVariables
    )

/**
 * Normalizes an update request into the shared comparison shape.
 *
 * @receiver Update request decoded from the signed payload.
 * @return Request-derived fields used for DTO comparison.
 */
private fun UpdateLocalMCPServerRequest.toNormalizedSignedPayload(): NormalizedSignedMcpServerPayload =
    NormalizedSignedMcpServerPayload(
        workerId = workerId,
        name = name,
        description = description,
        command = command,
        arguments = arguments,
        workingDirectory = workingDirectory,
        isEnabled = isEnabled,
        autoStartOnEnable = autoStartOnEnable,
        autoStartOnLaunch = autoStartOnLaunch,
        autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
        toolNamePrefix = toolNamePrefix,
        environmentVariables = environmentVariables,
        secretEnvironmentVariables = secretEnvironmentVariables
    )

/**
 * Lists the request-derived fields whose values do not match the relayed server DTO.
 *
 * @receiver Relayed Local MCP server DTO about to be trusted by the worker.
 * @param signedPayload Normalized request-derived fields decoded from the trusted signed payload.
 * @return Ordered list of mismatched field names.
 */
private fun LocalMCPServerDto.findMismatchedFields(
    signedPayload: NormalizedSignedMcpServerPayload
): List<String> = buildList {
    if (workerId != signedPayload.workerId) add("workerId")
    if (name != signedPayload.name) add("name")
    if (description != signedPayload.description) add("description")
    if (command != signedPayload.command) add("command")
    if (arguments != signedPayload.arguments) add("arguments")
    if (workingDirectory != signedPayload.workingDirectory) add("workingDirectory")
    if (isEnabled != signedPayload.isEnabled) add("isEnabled")
    if (autoStartOnEnable != signedPayload.autoStartOnEnable) add("autoStartOnEnable")
    if (autoStartOnLaunch != signedPayload.autoStartOnLaunch) add("autoStartOnLaunch")
    if (autoStopAfterInactivitySeconds != signedPayload.autoStopAfterInactivitySeconds) {
        add("autoStopAfterInactivitySeconds")
    }
    if (toolNamePrefix != signedPayload.toolNamePrefix) add("toolNamePrefix")
    if (environmentVariables != signedPayload.environmentVariables) add("environmentVariables")
    if (secretEnvironmentVariables != signedPayload.secretEnvironmentVariables) add("secretEnvironmentVariables")
}