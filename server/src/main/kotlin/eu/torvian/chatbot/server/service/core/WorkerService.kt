package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.WorkerChallengeDto
import eu.torvian.chatbot.common.models.worker.WorkerDto
import eu.torvian.chatbot.server.service.core.error.worker.AuthenticateWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.DeleteWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.RegisterWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.UpdateWorkerError

/**
 * Worker registration/authentication operations.
 */
interface WorkerService {
    /**
     * Registers a new worker.
     *
     * @param ownerUserId Owning user identifier.
     * @param workerUid Public worker UID generated during setup.
     * @param displayName Worker display name.
     * @param certificatePem PEM-encoded public certificate.
     * @param allowedScopes Logical worker scopes to persist.
     * @return Either registration error or the created worker.
     */
    suspend fun registerWorker(
        ownerUserId: Long,
        workerUid: String,
        displayName: String,
        certificatePem: String,
        allowedScopes: List<String>
    ): Either<RegisterWorkerError, WorkerDto>

    /**
     * Authenticates a worker using a one-time challenge response.
     *
     * @param workerUid Worker UID.
     * @param challengeId Challenge identifier.
     * @param signatureBase64 Base64-encoded challenge signature.
     * @return Either authentication error or the authenticated worker.
     */
    suspend fun authenticateWorker(
        workerUid: String,
        challengeId: String,
        signatureBase64: String
    ): Either<AuthenticateWorkerError, WorkerDto>

    /**
     * Creates a short-lived challenge used before service-token exchange.
     *
     * @param workerUid Worker UID.
     * @param certificateFingerprint Certificate fingerprint for self-identifying challenge scope.
     * @return Either authentication-precheck error or a new service-token challenge.
     */
    suspend fun createServiceTokenChallenge(
        workerUid: String,
        certificateFingerprint: String
    ): Either<AuthenticateWorkerError, WorkerChallengeDto>

    /**
     * Lists all workers registered by the specified owner.
     *
     * @param ownerUserId Owning user identifier.
     * @return List of [WorkerDto] objects; empty list if none exist.
     */
    suspend fun listWorkersByOwner(ownerUserId: Long): List<WorkerDto>

    /**
     * Updates a worker's display name and allowed scopes.
     *
     * Note: workerUid and certificateFingerprint are immutable.
     * This method verifies that the authenticated user owns the worker.
     *
     * @param ownerUserId Owning user identifier from JWT.
     * @param workerId Worker identifier to update.
     * @param displayName New display name.
     * @param allowedScopes New list of allowed scopes.
     * @return Either update error or the updated worker.
     */
    suspend fun updateWorker(
        ownerUserId: Long,
        workerId: Long,
        displayName: String,
        allowedScopes: List<String>
    ): Either<UpdateWorkerError, WorkerDto>

    /**
     * Deletes a worker by identifier.
     *
     * This method verifies that the authenticated user owns the worker.
     *
     * @param ownerUserId Owning user identifier from JWT.
     * @param workerId Worker identifier to delete.
     * @return Either delete error or Unit on success.
     */
    suspend fun deleteWorker(
        ownerUserId: Long,
        workerId: Long
    ): Either<DeleteWorkerError, Unit>
}
