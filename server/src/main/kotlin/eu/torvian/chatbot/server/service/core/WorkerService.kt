package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.worker.WorkerChallengeDto
import eu.torvian.chatbot.common.models.worker.WorkerDto
import eu.torvian.chatbot.server.service.core.error.worker.AuthenticateWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.RegisterWorkerError

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
}


