package eu.torvian.chatbot.worker.credential

import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileRules
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.SignedRequestPayloadDecodingResult
import eu.torvian.chatbot.common.security.decodePayloadOrNull
import eu.torvian.chatbot.worker.service.security.VerificationError
import eu.torvian.chatbot.worker.service.security.VerificationOptions
import eu.torvian.chatbot.worker.service.security.VerifiedSignedPayloadResult
import eu.torvian.chatbot.worker.service.security.VerificationService
import eu.torvian.chatbot.worker.service.security.verifyAndDecodeSignedPayload

/**
 * Default [CredentialProfileValidator].
 *
 * Verification reuses the worker trust store, then compares every request-derived field against the
 * relayed DTO. The comparison list and the request DTOs stay in lockstep: a field added to a request
 * but forgotten here would be signed yet unverified, so the server could change it without breaking
 * the signature.
 *
 * @property verificationService Worker trust-store verifier used for detached signature validation.
 * @property deploymentEnvironmentKeys Keys of the deployment-wide child environment, so a profile
 *   cannot silently override a value the deployment owns.
 */
class DefaultCredentialProfileValidator(
    private val verificationService: VerificationService,
    private val deploymentEnvironmentKeys: Set<String> = emptySet(),
) : CredentialProfileValidator {

    override suspend fun validate(
        profile: CredentialProfileDto,
        signedRequest: SignedRequest?,
        workerId: Long?,
    ): CredentialProfileValidationResult {
        if (workerId != null && profile.workerId != workerId) {
            return CredentialProfileValidationResult.ForeignWorker(expected = workerId, actual = profile.workerId)
        }

        val validation = verificationService.verifyAndDecodeSignedPayload(
            signedRequest = signedRequest,
            options = VerificationOptions(checkExpiration = false),
            decodePayload = ::decodeSignedPayload,
        )

        return when (validation) {
            null -> CredentialProfileValidationResult.MissingSignedRequest
            is VerifiedSignedPayloadResult.VerificationFailed -> validation.error.toValidationFailure()
            is VerifiedSignedPayloadResult.Verified -> {
                val mismatched = profile.findMismatchedFields(validation.payload)
                when {
                    mismatched.isNotEmpty() -> CredentialProfileValidationResult.DtoMismatch(mismatched)
                    else -> validateFields(profile)
                }
            }

            VerifiedSignedPayloadResult.MalformedPayload,
            VerifiedSignedPayloadResult.InvalidPayload ->
                CredentialProfileValidationResult.MalformedSignedPayload(
                    details = "Signed payload could not be decoded as a credential profile request"
                )
        }
    }

    /**
     * Applies the shared rules to the relayed profile after its signature has been verified.
     *
     * @param profile Relay between the signed payload and the cache.
     * @return Authorized, or a rejection listing the violated rules.
     */
    private fun validateFields(profile: CredentialProfileDto): CredentialProfileValidationResult {
        val violations = CredentialProfileRules.validate(profile.toCredentialProfile().toFields(), deploymentEnvironmentKeys)
        return if (violations.isEmpty()) {
            CredentialProfileValidationResult.Authorized
        } else {
            CredentialProfileValidationResult.ValidationFailed(violations)
        }
    }

    /**
     * Deserializes the exact signed payload as either supported profile mutation request type.
     *
     * @param signedRequest Detached signed request carrying the exact serialized payload string.
     * @return Structured decoding result for normalized request-derived fields.
     */
    private fun decodeSignedPayload(
        signedRequest: SignedRequest,
    ): SignedRequestPayloadDecodingResult<NormalizedCredentialProfilePayload> {
        signedRequest.decodePayloadOrNull<CreateCredentialProfileRequest>()?.let { payload ->
            return SignedRequestPayloadDecodingResult.Decoded(payload.toNormalized())
        }
        signedRequest.decodePayloadOrNull<UpdateCredentialProfileRequest>()?.let { payload ->
            return SignedRequestPayloadDecodingResult.Decoded(payload.toNormalized())
        }
        return SignedRequestPayloadDecodingResult.MalformedPayload
    }
}

/**
 * Request-derived profile fields used for DTO comparison.
 *
 * Server-generated fields are excluded, and a secret value is absent by construction.
 *
 * @property workerId Worker binding authorized by the signer.
 * @property name Profile name.
 * @property description Optional explanation shown to the model.
 * @property secretRef Secret reference.
 * @property command Executable the profile starts.
 * @property args Argument list.
 * @property channel Delivery channel.
 * @property envName Variable receiving the value or the file path.
 * @property fileName Single path component of the secret file.
 * @property askpassUsername Username the askpass script answers with.
 * @property isEnabled Whether the profile may be attached.
 * @property environmentVariables Non-secret environment entries.
 * @property askpassVariables Names receiving the askpass script path.
 * @property agentRoleIds Agent roles the signed body allows to attach the profile, compared as a set.
 */
private data class NormalizedCredentialProfilePayload(
    val workerId: Long,
    val name: String,
    val description: String?,
    val secretRef: String,
    val command: String,
    val args: List<String>,
    val channel: eu.torvian.chatbot.common.models.api.credential.CredentialChannel,
    val envName: String?,
    val fileName: String?,
    val askpassUsername: String?,
    val isEnabled: Boolean,
    val environmentVariables: Map<String, String>,
    val askpassVariables: List<String>,
    val agentRoleIds: Set<Long>,
)

/**
 * @receiver Create request decoded from a signed payload.
 * @return Request-derived fields used for DTO comparison.
 */
private fun CreateCredentialProfileRequest.toNormalized(): NormalizedCredentialProfilePayload =
    NormalizedCredentialProfilePayload(
        workerId = workerId,
        name = name,
        description = description,
        secretRef = secretRef,
        command = command,
        args = args,
        channel = channel,
        envName = envName,
        fileName = fileName,
        askpassUsername = askpassUsername,
        isEnabled = isEnabled,
        environmentVariables = environmentVariables,
        askpassVariables = askpassVariables,
        agentRoleIds = agentRoleIds,
    )

/**
 * @receiver Update request decoded from a signed payload.
 * @return Request-derived fields used for DTO comparison.
 */
private fun UpdateCredentialProfileRequest.toNormalized(): NormalizedCredentialProfilePayload =
    NormalizedCredentialProfilePayload(
        workerId = workerId,
        name = name,
        description = description,
        secretRef = secretRef,
        command = command,
        args = args,
        channel = channel,
        envName = envName,
        fileName = fileName,
        askpassUsername = askpassUsername,
        isEnabled = isEnabled,
        environmentVariables = environmentVariables,
        askpassVariables = askpassVariables,
        agentRoleIds = agentRoleIds,
    )

/**
 * Lists the request-derived fields whose values differ from the signed payload.
 *
 * @receiver Relayed profile about to be trusted by the worker.
 * @param signedPayload Normalized fields decoded from the trusted signed payload.
 * @return Ordered names of the mismatched fields.
 */
private fun CredentialProfileDto.findMismatchedFields(
    signedPayload: NormalizedCredentialProfilePayload,
): List<String> = buildList {
    if (workerId != signedPayload.workerId) add("workerId")
    if (name != signedPayload.name) add("name")
    if (description != signedPayload.description) add("description")
    if (secretRef != signedPayload.secretRef) add("secretRef")
    if (command != signedPayload.command) add("command")
    if (args != signedPayload.args) add("args")
    if (channel != signedPayload.channel) add("channel")
    if (envName != signedPayload.envName) add("envName")
    if (fileName != signedPayload.fileName) add("fileName")
    if (askpassUsername != signedPayload.askpassUsername) add("askpassUsername")
    if (isEnabled != signedPayload.isEnabled) add("isEnabled")
    if (environmentVariables != signedPayload.environmentVariables) add("environmentVariables")
    if (askpassVariables != signedPayload.askpassVariables) add("askpassVariables")
    // Sets compare by membership, so the same link set in another order is not a mismatch.
    if (agentRoleIds != signedPayload.agentRoleIds) add("agentRoleIds")
}

/**
 * Converts verification failures into the profile authorization failure model.
 *
 * @receiver Verification error produced by [VerificationService].
 * @return Structured profile validation failure.
 */
private fun VerificationError.toValidationFailure(): CredentialProfileValidationResult.Rejected = when (this) {
    is VerificationError.UnknownSigner -> CredentialProfileValidationResult.UnknownSigner(signerId = signerId)
    is VerificationError.InvalidSignature -> CredentialProfileValidationResult.InvalidSignature(
        details = cause?.toString()
    )
    is VerificationError.Expired -> CredentialProfileValidationResult.InvalidSignature(
        details = "Unexpected expiration rejection while expiration checks were disabled"
    )
}
