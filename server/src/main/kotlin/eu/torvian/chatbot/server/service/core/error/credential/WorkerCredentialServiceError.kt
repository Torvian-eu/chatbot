package eu.torvian.chatbot.server.service.core.error.credential

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.service.security.CredentialEncryptionError

/**
 * Logical failure returned by credential profile and secret management.
 *
 * No variant carries a secret value, ciphertext, or a wrapped data key: every field is an identifier,
 * an operator-authored name, or a validation sentence that names a field.
 */
sealed interface WorkerCredentialServiceError {
    /**
     * The addressed profile does not exist.
     *
     * @property profileId Profile identifier that was requested.
     */
    data class ProfileNotFound(val profileId: Long) : WorkerCredentialServiceError

    /**
     * The addressed secret does not exist.
     *
     * @property secretId Secret identifier that was requested.
     */
    data class SecretNotFound(val secretId: Long) : WorkerCredentialServiceError

    /**
     * The addressed worker does not exist.
     *
     * @property workerId Worker identifier that was requested.
     */
    data class WorkerNotFound(val workerId: Long) : WorkerCredentialServiceError

    /**
     * The authenticated user does not own the worker the row belongs to.
     *
     * @property userId Authenticated user.
     * @property workerId Worker whose ownership was rejected.
     */
    data class Unauthorized(val userId: Long, val workerId: Long) : WorkerCredentialServiceError

    /**
     * The profile fields violate the shared credential rules.
     *
     * @property violations Human-readable messages that never echo a value.
     */
    data class ValidationFailed(val violations: List<String>) : WorkerCredentialServiceError

    /**
     * A profile with the same name already exists on the worker.
     *
     * @property workerId Worker the name must be unique on.
     * @property name Profile name that collided.
     */
    data class DuplicateProfileName(val workerId: Long, val name: String) : WorkerCredentialServiceError

    /**
     * A secret with the same reference already exists on the worker.
     *
     * @property workerId Worker the reference must be unique on.
     * @property reference Reference that collided.
     */
    data class DuplicateSecretReference(val workerId: Long, val reference: String) : WorkerCredentialServiceError

    /**
     * The profile references a secret the worker does not have.
     *
     * @property workerId Worker the profile is bound to.
     * @property reference Reference that could not be resolved.
     */
    data class SecretNotAssigned(val workerId: Long, val reference: String) : WorkerCredentialServiceError

    /**
     * A supplied link set names agent roles that do not exist or are not owned by the caller.
     *
     * The whole write is refused, so a profile never carries a partially applied link set.
     *
     * @property agentRoleIds Offending role ids, as supplied by the caller.
     */
    data class AgentRoleNotFound(val agentRoleIds: List<Long>) : WorkerCredentialServiceError

    /**
     * A secret update tried to change the reference a profile may already depend on.
     *
     * Replacing the value is the supported edit; a rename would silently break the profiles that name
     * the old reference, so it is refused instead of cascading.
     *
     * @property secretId Secret identifier.
     */
    data class SecretReferenceImmutable(val secretId: Long) : WorkerCredentialServiceError

    /**
     * Encryption of a new secret value failed.
     *
     * @property error Underlying cryptographic failure; never contains the plaintext.
     */
    data class EncryptionFailed(val error: CredentialEncryptionError) : WorkerCredentialServiceError
}

/**
 * Converts a credential service error to an API error.
 *
 * @receiver Service error to convert.
 * @return Structured API error response.
 */
fun WorkerCredentialServiceError.toApiError(): ApiError = when (this) {
    is WorkerCredentialServiceError.ProfileNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Credential profile not found", "profileId" to profileId.toString())

    is WorkerCredentialServiceError.SecretNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Credential secret not found", "secretId" to secretId.toString())

    is WorkerCredentialServiceError.WorkerNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Worker not found", "workerId" to workerId.toString())

    is WorkerCredentialServiceError.Unauthorized ->
        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Worker is not owned by the authenticated user")

    is WorkerCredentialServiceError.ValidationFailed ->
        apiError(CommonApiErrorCodes.VALIDATION_FAILED, "Invalid credential profile", "violations" to violations.joinToString("; "))

    is WorkerCredentialServiceError.DuplicateProfileName ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "A credential profile with this name already exists")

    is WorkerCredentialServiceError.DuplicateSecretReference ->
        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "A credential secret with this reference already exists")

    is WorkerCredentialServiceError.SecretNotAssigned ->
        apiError(
            CommonApiErrorCodes.FAILED_PRECONDITION,
            "The referenced credential secret is not assigned to this worker",
            "reference" to reference
        )

    is WorkerCredentialServiceError.AgentRoleNotFound ->
        apiError(
            CommonApiErrorCodes.VALIDATION_FAILED,
            "agentRoleIds contains agent roles that do not exist or are not owned by the caller",
            "agentRoleIds" to agentRoleIds.joinToString(",")
        )

    is WorkerCredentialServiceError.SecretReferenceImmutable ->
        apiError(
            CommonApiErrorCodes.INVALID_STATE,
            "A credential secret's reference cannot be changed; create a new secret and update the profiles"
        )

    is WorkerCredentialServiceError.EncryptionFailed ->
        apiError(CommonApiErrorCodes.INTERNAL, "Credential encryption failed", "reason" to error.describe())
}

/**
 * @return One-line description of the cryptographic failure, without secret material.
 */
private fun CredentialEncryptionError.describe(): String = when (this) {
    is CredentialEncryptionError.InvalidCertificate -> "invalid certificate: $reason"
    is CredentialEncryptionError.UnsupportedKey -> "unsupported key: $reason"
    is CredentialEncryptionError.CryptoFailure -> "crypto failure: $reason"
    is CredentialEncryptionError.ValueTooLarge -> "value too large: $actualBytes > $maxBytes"
}
