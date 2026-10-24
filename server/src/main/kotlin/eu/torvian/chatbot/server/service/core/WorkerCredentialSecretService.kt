package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError

/**
 * Manages worker-bound credential secrets.
 *
 * The plaintext is accepted only on write. It is encrypted to the worker certificate before it reaches
 * storage, and no read method can return it: the server keeps ciphertext it cannot decrypt.
 */
interface WorkerCredentialSecretService {
    /**
     * Encrypts and stores a new secret for one worker.
     *
     * @param userId Authenticated user; must own the worker.
     * @param request Plaintext value plus its worker and reference.
     * @return Either a typed failure or the stored secret metadata.
     */
    suspend fun createSecret(
        userId: Long,
        request: CreateCredentialSecretRequest,
    ): Either<WorkerCredentialServiceError, CredentialSecretMetadataDto>

    /**
     * Replaces the stored value of an existing secret.
     *
     * @param userId Authenticated user; must own the worker.
     * @param secretId Secret being replaced.
     * @param request New plaintext value and the unchanged reference.
     * @return Either a typed failure or the stored secret metadata.
     */
    suspend fun updateSecret(
        userId: Long,
        secretId: Long,
        request: UpdateCredentialSecretRequest,
    ): Either<WorkerCredentialServiceError, CredentialSecretMetadataDto>

    /**
     * Deletes a secret.
     *
     * Profiles that reference it are deliberately kept: they stay valid metadata and fail at attach.
     *
     * @param userId Authenticated user; must own the worker.
     * @param secretId Secret to delete.
     * @return Either a typed failure or the secret metadata as it was before deletion.
     */
    suspend fun deleteSecret(
        userId: Long,
        secretId: Long,
    ): Either<WorkerCredentialServiceError, CredentialSecretMetadataDto>

    /**
     * @param userId Authenticated user; must own the worker.
     * @param workerId Worker whose secrets are wanted.
     * @return Either a typed failure or metadata for every secret of the worker.
     */
    suspend fun getSecrets(
        userId: Long,
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<CredentialSecretMetadataDto>>

    /**
     * @param userId Authenticated user; must own the worker.
     * @param secretId Secret to read.
     * @return Either a typed failure or the secret metadata.
     */
    suspend fun getSecret(
        userId: Long,
        secretId: Long,
    ): Either<WorkerCredentialServiceError, CredentialSecretMetadataDto>

    /**
     * Reads the encrypted secrets a worker is assigned.
     *
     * Called on the worker-authenticated bootstrap path, so it is scoped by worker alone.
     *
     * @param workerId Worker whose secrets are wanted.
     * @return Either a typed failure or the worker's encrypted secrets.
     */
    suspend fun getAssignedSecrets(
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<WorkerCredentialSecretDto>>

    /**
     * Reads one encrypted secret, for relay to its worker.
     *
     * @param secretId Secret to read.
     * @return Either a typed failure or the encrypted secret.
     */
    suspend fun getAssignedSecret(
        secretId: Long,
    ): Either<WorkerCredentialServiceError, WorkerCredentialSecretDto>
}
