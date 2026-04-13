package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.WorkerAuthChallengeEntity
import eu.torvian.chatbot.server.data.entities.WorkerEntity

/**
 * DAO for worker identity and challenge persistence.
 */
interface WorkerDao {
    /**
     * Creates a new worker.
     *
     * @param ownerUserId Owning user identifier.
     * @param workerUid Public worker UID generated during setup.
     * @param displayName Worker display name.
     * @param certificatePem PEM-encoded public certificate.
     * @param certificateFingerprint SHA-256 certificate fingerprint.
     * @param allowedScopes Logical worker scopes to persist.
     * @return Either duplicate fingerprint error or the created [WorkerEntity].
     */
    suspend fun createWorker(
        ownerUserId: Long,
        workerUid: String,
        displayName: String,
        certificatePem: String,
        certificateFingerprint: String,
        allowedScopes: List<String>
    ): Either<WorkerError, WorkerEntity>

    /**
     * Retrieves a worker by identifier.
     *
     * @param workerId Worker identifier.
     * @return Either not-found error or the matching [WorkerEntity].
     */
    suspend fun getWorkerById(workerId: Long): Either<WorkerError.NotFound, WorkerEntity>

    /**
     * Retrieves a worker by public UID.
     *
     * @param workerUid Worker UID.
     * @return Either not-found error or the matching [WorkerEntity].
     */
    suspend fun getWorkerByUid(workerUid: String): Either<WorkerError.UidNotFound, WorkerEntity>

    /**
     * Retrieves a worker by certificate fingerprint, if present.
     *
     * @param certificateFingerprint SHA-256 certificate fingerprint.
     * @return Matching [WorkerEntity] or null when not found.
     */
    suspend fun getWorkerByFingerprint(certificateFingerprint: String): WorkerEntity?

    /**
     * Records last successful worker activity.
     *
     * @param workerId Worker identifier.
     * @param lastSeenAtEpochMs Last-seen timestamp in epoch milliseconds.
     * @return Either not-found error or Unit when updated.
     */
    suspend fun updateLastSeen(workerId: Long, lastSeenAtEpochMs: Long): Either<WorkerError.NotFound, Unit>

    /**
     * Persists a one-time challenge used for proof-of-possession.
     *
     * @param workerId Worker identifier.
     * @param challengeId Challenge identifier.
     * @param challenge Plain-text challenge payload.
     * @param expiresAtEpochMs Expiration timestamp in epoch milliseconds.
     * @return Created [WorkerAuthChallengeEntity].
     */
    suspend fun createChallenge(
        workerId: Long,
        challengeId: String,
        challenge: String,
        expiresAtEpochMs: Long
    ): WorkerAuthChallengeEntity

    /**
     * Loads a non-expired, unconsumed challenge.
     *
     * @param workerId Worker identifier.
     * @param challengeId Challenge identifier.
     * @param nowEpochMs Current timestamp in epoch milliseconds.
     * @return Either invalid-challenge error or a valid [WorkerAuthChallengeEntity].
     */
    suspend fun getChallenge(
        workerId: Long,
        challengeId: String,
        nowEpochMs: Long
    ): Either<WorkerError.InvalidChallenge, WorkerAuthChallengeEntity>

    /**
     * Marks a challenge as consumed.
     *
     * @param challengeId Challenge identifier.
     * @return Either invalid-challenge error or Unit when consumed.
     */
    suspend fun consumeChallenge(challengeId: String): Either<WorkerError.InvalidChallenge, Unit>
}

