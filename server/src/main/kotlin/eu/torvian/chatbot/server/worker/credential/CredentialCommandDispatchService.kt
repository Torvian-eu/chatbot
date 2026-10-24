package eu.torvian.chatbot.server.worker.credential

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.common.models.api.credential.WorkerDispatchFailure

/**
 * Pushes one persisted credential change to its worker.
 *
 * The result is a classified failure rather than a transport error, because the caller reports it to
 * the operator through a DTO and must never render worker- or transport-supplied text.
 */
interface CredentialCommandDispatchService {
    /**
     * @param workerId Worker the profile is bound to.
     * @param signedProfile Stored profile plus the signature the worker will verify.
     * @return Either the classified delivery failure or success.
     */
    suspend fun createProfile(
        workerId: Long,
        signedProfile: SignedCredentialProfileDto,
    ): Either<WorkerDispatchFailure, Unit>

    /**
     * @param workerId Worker the profile is bound to.
     * @param signedProfile Stored profile plus the signature the worker will verify.
     * @return Either the classified delivery failure or success.
     */
    suspend fun updateProfile(
        workerId: Long,
        signedProfile: SignedCredentialProfileDto,
    ): Either<WorkerDispatchFailure, Unit>

    /**
     * @param workerId Worker whose cache should drop the profile.
     * @param profileId Profile identifier.
     * @return Either the classified delivery failure or success.
     */
    suspend fun deleteProfile(
        workerId: Long,
        profileId: Long,
    ): Either<WorkerDispatchFailure, Unit>

    /**
     * @param workerId Worker the secret is bound to.
     * @param secret Stored secret, ciphertext only.
     * @return Either the classified delivery failure or success.
     */
    suspend fun createSecret(
        workerId: Long,
        secret: WorkerCredentialSecretDto,
    ): Either<WorkerDispatchFailure, Unit>

    /**
     * @param workerId Worker the secret is bound to.
     * @param secret Stored secret, ciphertext only.
     * @return Either the classified delivery failure or success.
     */
    suspend fun updateSecret(
        workerId: Long,
        secret: WorkerCredentialSecretDto,
    ): Either<WorkerDispatchFailure, Unit>

    /**
     * @param workerId Worker whose cache should drop the secret.
     * @param secretId Secret identifier.
     * @return Either the classified delivery failure or success.
     */
    suspend fun deleteSecret(
        workerId: Long,
        secretId: Long,
    ): Either<WorkerDispatchFailure, Unit>
}
