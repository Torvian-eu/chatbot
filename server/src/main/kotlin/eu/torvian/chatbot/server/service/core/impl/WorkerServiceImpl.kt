package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.worker.WorkerChallengeDto
import eu.torvian.chatbot.common.models.worker.WorkerDto
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.mappers.toWorkerDto
import eu.torvian.chatbot.server.service.core.WorkerService
import eu.torvian.chatbot.server.service.core.error.worker.AuthenticateWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.DeleteWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.RegisterWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.UpdateWorkerError
import eu.torvian.chatbot.server.service.security.CertificateService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Worker service implementation for worker registration and service-token authentication.
 *
 * This class intentionally excludes worker job/delegation behavior.
 *
 * @property workerDao DAO for worker and challenge persistence.
 * @property certificateService Certificate parsing and signature verification service.
 * @property transactionScope Transaction scope used for service operations.
 */
class WorkerServiceImpl(
    private val workerDao: WorkerDao,
    private val certificateService: CertificateService,
    private val transactionScope: TransactionScope
) : WorkerService {

    override suspend fun registerWorker(
        ownerUserId: Long,
        workerUid: String,
        displayName: String,
        certificatePem: String,
        allowedScopes: List<String>
    ): Either<RegisterWorkerError, WorkerDto> = transactionScope.transaction {
        either {
            logger.debug("Registering worker request received (ownerUserId={}, workerUid={}, displayName={})", ownerUserId, workerUid, displayName)
            ensure(displayName.isNotBlank()) { RegisterWorkerError.InvalidInput("displayName cannot be blank") }
            ensure(workerUid.isNotBlank()) { RegisterWorkerError.InvalidInput("workerUid cannot be blank") }

            val certificate = try {
                certificateService.parseCertificate(certificatePem)
            } catch (e: IllegalArgumentException) {
                logger.warn("Worker registration failed due to invalid certificate (ownerUserId={})", ownerUserId)
                raise(RegisterWorkerError.InvalidCertificate(e.message ?: "Invalid certificate"))
            }

            val fingerprint = certificateService.computeCertificateFingerprint(certificate)
            val worker = withError({ error: WorkerError ->
                when (error) {
                    is WorkerError.DuplicateCertificateFingerprint -> {
                        logger.warn("Worker registration failed: duplicate certificate fingerprint={}", error.fingerprint)
                        RegisterWorkerError.CertificateAlreadyRegistered(error.fingerprint)
                    }

                    is WorkerError.DuplicateWorkerUid -> {
                        logger.warn("Worker registration failed: duplicate workerUid={}", error.workerUid)
                        RegisterWorkerError.WorkerUidAlreadyRegistered(error.workerUid)
                    }

                    else -> error("Unexpected worker DAO error during registration: $error")
                }
            }) {
                workerDao.createWorker(
                    ownerUserId = ownerUserId,
                    workerUid = workerUid.trim(),
                    displayName = displayName.trim(),
                    certificatePem = certificatePem,
                    certificateFingerprint = fingerprint,
                    allowedScopes = allowedScopes.distinct()
                ).bind()
            }

            logger.info("Worker registered successfully (workerUid={}, ownerUserId={})", worker.workerUid, worker.ownerUserId)
            worker.toWorkerDto()
        }
    }

    override suspend fun authenticateWorker(
        workerUid: String,
        challengeId: String,
        signatureBase64: String
    ): Either<AuthenticateWorkerError, WorkerDto> = transactionScope.transaction {
        either {
            logger.debug("Authenticating worker (workerUid={}, challengeId={})", workerUid, challengeId)
            val worker = withError({ _: WorkerError.UidNotFound -> AuthenticateWorkerError.WorkerNotFound(workerUid) }) {
                workerDao.getWorkerByUid(workerUid).bind()
            }

            val challenge = withError({ _: WorkerError.InvalidChallenge -> AuthenticateWorkerError.InvalidChallenge(challengeId) }) {
                workerDao.getChallenge(worker.id, challengeId, System.currentTimeMillis()).bind()
            }

            val cert = certificateService.parseCertificate(worker.certificatePem)
            if (!certificateService.verifySignature(challenge.challenge, signatureBase64, cert)) {
                logger.warn("Worker authentication failed: invalid signature (workerUid={}, challengeId={})", workerUid, challengeId)
                raise(AuthenticateWorkerError.InvalidSignature("Signature verification failed"))
            }

            withError({ _: WorkerError.InvalidChallenge -> AuthenticateWorkerError.InvalidChallenge(challengeId) }) {
                workerDao.consumeChallenge(challengeId).bind()
            }

            workerDao.updateLastSeen(worker.id, System.currentTimeMillis())
            logger.info("Worker authenticated successfully (workerUid={})", workerUid)

            worker.toWorkerDto()
        }
    }

    override suspend fun createServiceTokenChallenge(
        workerUid: String,
        certificateFingerprint: String
    ): Either<AuthenticateWorkerError, WorkerChallengeDto> =
        transactionScope.transaction {
            either {
                logger.debug("Creating service token challenge (workerUid={})", workerUid)
                val worker = workerDao.getWorkerByFingerprint(certificateFingerprint)
                    ?: run {
                        logger.warn("Challenge request failed: unknown fingerprint")
                        raise(AuthenticateWorkerError.WorkerNotFound(workerUid))
                    }

                // Keep challenge issuance scoped to the caller-provided worker identity.
                ensure(worker.workerUid == workerUid) {
                    logger.warn("Challenge request identity mismatch (workerUid={})", workerUid)
                    AuthenticateWorkerError.WorkerNotFound(workerUid)
                }

                createChallenge(worker.id, worker.workerUid)
            }
        }

    override suspend fun listWorkersByOwner(ownerUserId: Long): List<WorkerDto> =
        transactionScope.transaction {
            workerDao.getWorkersByOwnerId(ownerUserId).map { it.toWorkerDto() }
        }

    override suspend fun updateWorker(
        ownerUserId: Long,
        workerId: Long,
        displayName: String,
        allowedScopes: List<String>
    ): Either<UpdateWorkerError, WorkerDto> = transactionScope.transaction {
        either {
            logger.debug("Updating worker (workerId={}, ownerUserId={})", workerId, ownerUserId)

            // Fetch the worker first to verify ownership
            val worker = withError({ _: WorkerError.NotFound -> UpdateWorkerError.NotFound(workerId) }) {
                workerDao.getWorkerById(workerId).bind()
            }

            // Verify ownership
            ensure(worker.ownerUserId == ownerUserId) {
                logger.warn("Worker update failed: ownership mismatch (workerId={}, requestedOwner={}, actualOwner={})",
                    workerId, ownerUserId, worker.ownerUserId)
                UpdateWorkerError.Forbidden(workerId, worker.ownerUserId)
            }

            // Update the worker
            val updatedWorker = withError({ _: WorkerError.NotFound -> UpdateWorkerError.NotFound(workerId) }) {
                workerDao.updateWorker(
                    id = workerId,
                    displayName = displayName.trim(),
                    allowedScopes = allowedScopes.distinct()
                ).bind()
            }

            logger.info("Worker updated successfully (workerId={})", workerId)
            updatedWorker.toWorkerDto()
        }
    }

    override suspend fun deleteWorker(
        ownerUserId: Long,
        workerId: Long
    ): Either<DeleteWorkerError, Unit> = transactionScope.transaction {
        either {
            logger.debug("Deleting worker (workerId={}, ownerUserId={})", workerId, ownerUserId)

            // Fetch the worker first to verify ownership
            val worker = withError({ _: WorkerError.NotFound -> DeleteWorkerError.NotFound(workerId) }) {
                workerDao.getWorkerById(workerId).bind()
            }

            // Verify ownership
            ensure(worker.ownerUserId == ownerUserId) {
                logger.warn("Worker deletion failed: ownership mismatch (workerId={}, requestedOwner={}, actualOwner={})",
                    workerId, ownerUserId, worker.ownerUserId)
                DeleteWorkerError.Forbidden(workerId, worker.ownerUserId)
            }

            // Delete the worker
            withError({ _: WorkerError.NotFound -> DeleteWorkerError.NotFound(workerId) }) {
                workerDao.deleteWorker(workerId).bind()
            }

            logger.info("Worker deleted successfully (workerId={})", workerId)
        }
    }

    /**
     * @param workerId Worker identifier.
     * @param workerUid Public worker UID.
     * @return Generated challenge DTO.
     */
    private suspend fun createChallenge(workerId: Long, workerUid: String): WorkerChallengeDto {
        val now = Clock.System.now()
        val expiresAt = now.plus(CHALLENGE_TTL)
        val challengeId = randomId()
        // Challenge payload is deterministic enough for auditing and signature verification.
        val challenge = "worker:$workerUid:${challengeId}:${now.toEpochMilliseconds()}"

        workerDao.createChallenge(
            workerId = workerId,
            challengeId = challengeId,
            challenge = challenge,
            expiresAtEpochMs = expiresAt.toEpochMilliseconds()
        )

        logger.debug("Issued worker challenge (workerId={}, workerUid={}, challengeId={})", workerId, workerUid, challengeId)

        return WorkerChallengeDto(
            challengeId = challengeId,
            challenge = challenge,
            expiresAt = expiresAt
        )
    }


    /**
     * Generates a random alpha-numeric identifier for worker challenges.
     *
     * @param length Output identifier length.
     * @return Random alpha-numeric identifier string.
     */
    private fun randomId(length: Int = 32): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(length) {
            repeat(length) {
                append(chars[Random.nextInt(chars.length)])
            }
        }
    }

    companion object {
        private val logger: Logger = LogManager.getLogger(WorkerServiceImpl::class.java)
        private val CHALLENGE_TTL = 10.minutes
    }
}

