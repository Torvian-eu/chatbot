package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import kotlinx.serialization.Serializable

/**
 * Request data carried by `credential.profile.create` worker commands.
 *
 * @property signedProfile Persisted profile plus the detached device signature that authorized it.
 */
@Serializable
data class WorkerCredentialProfileCreateCommandData(
    val signedProfile: SignedCredentialProfileDto
)

/**
 * Request data carried by `credential.profile.update` worker commands.
 *
 * @property signedProfile Persisted profile plus the detached device signature that authorized it.
 */
@Serializable
data class WorkerCredentialProfileUpdateCommandData(
    val signedProfile: SignedCredentialProfileDto
)

/**
 * Request data carried by `credential.profile.delete` worker commands.
 *
 * @property profileId Persisted profile identifier to remove from the worker cache.
 */
@Serializable
data class WorkerCredentialProfileDeleteCommandData(
    val profileId: Long
)

/**
 * Request data carried by `credential.secret.create` worker commands.
 *
 * Secrets are unsigned because the server produces the ciphertext and no device can sign it; the
 * worker accepts the row after the bootstrap `workerId` check.
 *
 * @property secret Encrypted secret bound to the worker; ciphertext only.
 */
@Serializable
data class WorkerCredentialSecretCreateCommandData(
    val secret: WorkerCredentialSecretDto
)

/**
 * Request data carried by `credential.secret.update` worker commands.
 *
 * @property secret Encrypted secret bound to the worker; ciphertext only.
 */
@Serializable
data class WorkerCredentialSecretUpdateCommandData(
    val secret: WorkerCredentialSecretDto
)

/**
 * Request data carried by `credential.secret.delete` worker commands.
 *
 * @property secretId Persisted secret identifier to remove from the worker cache.
 */
@Serializable
data class WorkerCredentialSecretDeleteCommandData(
    val secretId: Long
)

/**
 * Result data emitted for successful `credential.profile.create` worker commands.
 *
 * @property profileId Identifier of the profile now held by the worker cache.
 */
@Serializable
data class WorkerCredentialProfileCreateResultData(
    val profileId: Long
)

/**
 * Result data emitted for successful `credential.profile.update` worker commands.
 *
 * @property profileId Identifier of the profile now held by the worker cache.
 */
@Serializable
data class WorkerCredentialProfileUpdateResultData(
    val profileId: Long
)

/**
 * Result data emitted for successful `credential.profile.delete` worker commands.
 *
 * @property profileId Identifier of the profile removed from the worker cache.
 */
@Serializable
data class WorkerCredentialProfileDeleteResultData(
    val profileId: Long
)

/**
 * Result data emitted for successful `credential.secret.create` worker commands.
 *
 * @property secretId Identifier of the secret now held by the worker cache.
 */
@Serializable
data class WorkerCredentialSecretCreateResultData(
    val secretId: Long
)

/**
 * Result data emitted for successful `credential.secret.update` worker commands.
 *
 * @property secretId Identifier of the secret now held by the worker cache.
 */
@Serializable
data class WorkerCredentialSecretUpdateResultData(
    val secretId: Long
)

/**
 * Result data emitted for successful `credential.secret.delete` worker commands.
 *
 * @property secretId Identifier of the secret removed from the worker cache.
 */
@Serializable
data class WorkerCredentialSecretDeleteResultData(
    val secretId: Long
)

/**
 * Result data emitted for a rejected credential sync command.
 *
 * @property entityId Profile or secret identifier the command targeted, when one was decoded.
 * @property code Stable machine-readable rejection code.
 * @property message Human-readable rejection message; never contains a secret value or ciphertext.
 * @property details Optional additional diagnostics.
 */
@Serializable
data class WorkerCredentialCommandErrorResultData(
    val entityId: Long? = null,
    val code: String,
    val message: String,
    val details: String? = null
)
