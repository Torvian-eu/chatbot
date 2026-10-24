package eu.torvian.chatbot.common.models.api.credential

import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.Serializable

/**
 * Worker-facing wrapper pairing a credential profile with the device signature that authorized it.
 *
 * The profile body is user-authored and fixed the command the profile will run, so it must carry a
 * device signature the worker can verify. A wrapper without [signedRequest] is rejected by the worker
 * rather than trusted.
 *
 * @property profile Profile as persisted on the server.
 * @property signedRequest Detached signature over the persisted profile payload, when available.
 */
@Serializable
data class SignedCredentialProfileDto(
    val profile: CredentialProfileDto,
    val signedRequest: SignedRequest? = null,
)

/**
 * Everything a worker is assigned, returned by the credential bootstrap endpoint.
 *
 * [workerId] is resolved by the server from the calling worker's credentials and is the only identity
 * the worker can compare rows against. It catches a server-side join or pagination mistake; it cannot
 * detect a hostile server, because a server that can mislabel the field can mislabel the rows.
 * Decryptability is the only cryptographic gate on a secret.
 *
 * @property workerId Worker the rows belong to, resolved server-side.
 * @property profiles Profiles bound to this worker, each with its persisted device signature.
 * @property secrets Encrypted secrets bound to this worker; ciphertext only.
 */
@Serializable
data class AssignedCredentialsDto(
    val workerId: Long,
    val profiles: List<SignedCredentialProfileDto> = emptyList(),
    val secrets: List<WorkerCredentialSecretDto> = emptyList(),
)
