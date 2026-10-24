package eu.torvian.chatbot.server.worker.credential

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialDeleteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError

/**
 * Coordinates credential persistence with best-effort delivery to the worker.
 *
 * A write is committed first and only then pushed, so a delivery failure never fails or rolls back the
 * write: the server cannot re-derive a secret plaintext, and an older worker rejects command types it
 * does not know, which would otherwise make a server-first rollout impossible. The delivery outcome is
 * returned to the caller instead of being swallowed, and the worker reconciles at its next bootstrap.
 */
interface CredentialSyncService {
    /**
     * Persists a profile and pushes it to its worker.
     *
     * @param userId Authenticated user; must own the target worker.
     * @param request Signed profile body.
     * @param signedRequest Detached signature over the exact raw request body.
     * @return Either a persistence failure or the stored profile with its delivery outcome.
     */
    suspend fun createProfile(
        userId: Long,
        request: CreateCredentialProfileRequest,
        signedRequest: SignedRequest,
    ): Either<WorkerCredentialServiceError, CredentialProfileWriteResultDto>

    /**
     * Persists a profile update and pushes it to its worker.
     *
     * A profile that changes worker is removed from the previous worker's cache before the new worker
     * receives it.
     *
     * @param userId Authenticated user; must own both workers.
     * @param profileId Profile being replaced.
     * @param request Signed replacement body.
     * @param signedRequest Detached signature over the exact raw request body.
     * @return Either a persistence failure or the stored profile with its delivery outcome.
     */
    suspend fun updateProfile(
        userId: Long,
        profileId: Long,
        request: UpdateCredentialProfileRequest,
        signedRequest: SignedRequest,
    ): Either<WorkerCredentialServiceError, CredentialProfileWriteResultDto>

    /**
     * Deletes a profile and drops it from its worker's cache.
     *
     * @param userId Authenticated user; must own the profile's worker.
     * @param profileId Profile to delete.
     * @return Either a persistence failure or the deleted identifier with its delivery outcome.
     */
    suspend fun deleteProfile(
        userId: Long,
        profileId: Long,
    ): Either<WorkerCredentialServiceError, CredentialDeleteResultDto>

    /**
     * Persists an encrypted secret and pushes it to its worker.
     *
     * @param userId Authenticated user; must own the target worker.
     * @param request Plaintext value, worker and reference.
     * @return Either a persistence failure or the stored secret metadata with its delivery outcome.
     */
    suspend fun createSecret(
        userId: Long,
        request: CreateCredentialSecretRequest,
    ): Either<WorkerCredentialServiceError, CredentialSecretWriteResultDto>

    /**
     * Replaces a secret value and pushes it to its worker.
     *
     * @param userId Authenticated user; must own the worker.
     * @param secretId Secret being replaced.
     * @param request New plaintext value and the unchanged reference.
     * @return Either a persistence failure or the stored secret metadata with its delivery outcome.
     */
    suspend fun updateSecret(
        userId: Long,
        secretId: Long,
        request: UpdateCredentialSecretRequest,
    ): Either<WorkerCredentialServiceError, CredentialSecretWriteResultDto>

    /**
     * Deletes a secret and drops it from its worker's cache.
     *
     * @param userId Authenticated user; must own the worker.
     * @param secretId Secret to delete.
     * @return Either a persistence failure or the deleted identifier with its delivery outcome.
     */
    suspend fun deleteSecret(
        userId: Long,
        secretId: Long,
    ): Either<WorkerCredentialServiceError, CredentialDeleteResultDto>
}
