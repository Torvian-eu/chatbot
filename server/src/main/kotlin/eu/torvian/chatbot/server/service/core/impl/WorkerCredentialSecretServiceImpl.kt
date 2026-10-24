package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileRules
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretLimits
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.server.data.dao.WorkerCredentialSecretDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.WorkerCredentialPersistenceError
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.CreateWorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.UpdateWorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialSecretEntity
import eu.torvian.chatbot.server.service.core.WorkerCredentialSecretService
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError
import eu.torvian.chatbot.server.service.security.CredentialSecretEncryptionService
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default [WorkerCredentialSecretService].
 *
 * The plaintext value exists only for the duration of one encryption call. Nothing in this class logs
 * or returns it, and the worker binding is taken from the stored row on update so a secret can never
 * move between workers.
 *
 * @property secretDao Persistence gateway for encrypted secrets.
 * @property workerDao Persistence gateway used for ownership checks and the worker certificate.
 * @property encryptionService Encryptor that wraps each value to the worker's certificate.
 * @property transactionScope Transaction boundary for each operation.
 * @property logger Diagnostic sink; receives identifiers and references only.
 */
class WorkerCredentialSecretServiceImpl(
    private val secretDao: WorkerCredentialSecretDao,
    private val workerDao: WorkerDao,
    private val encryptionService: CredentialSecretEncryptionService,
    private val transactionScope: TransactionScope,
    private val logger: Logger = LogManager.getLogger(WorkerCredentialSecretServiceImpl::class.java),
) : WorkerCredentialSecretService {

    override suspend fun createSecret(
        userId: Long,
        request: CreateCredentialSecretRequest,
    ): Either<WorkerCredentialServiceError, CredentialSecretMetadataDto> =
        transactionScope.transaction {
            either {
                val worker = loadOwnedWorker(userId, request.workerId).bind()
                ensureReferenceUsable(request.reference)
                ensureValueUsable(request.value)
                val payload = encryptionService.encrypt(request.value, worker.certificatePem)
                    .mapLeft { WorkerCredentialServiceError.EncryptionFailed(it) }
                    .bind()

                val created = secretDao.createSecret(
                    CreateWorkerCredentialSecretEntity(
                        workerId = worker.id,
                        reference = request.reference,
                        encryptedSecret = payload.encryptedSecret,
                        encryptedDek = payload.encryptedDek,
                        algorithm = payload.algorithm,
                        workerCertificateFingerprint = payload.workerCertificateFingerprint,
                    )
                ).mapLeft { it.toServiceError() }.bind()

                logger.info("Created credential secret {} for worker {}", created.reference, created.workerId)
                created.toMetadataDto()
            }
        }

    override suspend fun updateSecret(
        userId: Long,
        secretId: Long,
        request: UpdateCredentialSecretRequest,
    ): Either<WorkerCredentialServiceError, CredentialSecretMetadataDto> =
        transactionScope.transaction {
            either {
                val existing = loadSecret(secretId).bind()
                val worker = loadOwnedWorker(userId, existing.workerId).bind()
                ensureReferenceUsable(request.reference)
                ensureValueUsable(request.value)
                ensure(request.reference == existing.reference) {
                    WorkerCredentialServiceError.SecretReferenceImmutable(secretId)
                }

                val payload = encryptionService.encrypt(request.value, worker.certificatePem)
                    .mapLeft { WorkerCredentialServiceError.EncryptionFailed(it) }
                    .bind()

                val updated = secretDao.updateSecret(
                    secretId,
                    UpdateWorkerCredentialSecretEntity(
                        reference = existing.reference,
                        encryptedSecret = payload.encryptedSecret,
                        encryptedDek = payload.encryptedDek,
                        algorithm = payload.algorithm,
                        workerCertificateFingerprint = payload.workerCertificateFingerprint,
                    )
                ).mapLeft { it.toServiceError() }.bind()

                logger.info("Replaced credential secret {} for worker {}", updated.reference, updated.workerId)
                updated.toMetadataDto()
            }
        }

    override suspend fun deleteSecret(
        userId: Long,
        secretId: Long,
    ): Either<WorkerCredentialServiceError, CredentialSecretMetadataDto> =
        transactionScope.transaction {
            either {
                val existing = loadSecret(secretId).bind()
                loadOwnedWorker(userId, existing.workerId).bind()
                val deleted = secretDao.deleteSecret(secretId).mapLeft { it.toServiceError() }.bind()
                logger.info("Deleted credential secret {} of worker {}", deleted.reference, deleted.workerId)
                deleted.toMetadataDto()
            }
        }

    override suspend fun getSecrets(
        userId: Long,
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<CredentialSecretMetadataDto>> =
        transactionScope.transaction {
            either {
                loadOwnedWorker(userId, workerId).bind()
                secretDao.getSecretsByWorkerId(workerId).map { it.toMetadataDto() }
            }
        }

    override suspend fun getSecret(
        userId: Long,
        secretId: Long,
    ): Either<WorkerCredentialServiceError, CredentialSecretMetadataDto> =
        transactionScope.transaction {
            either {
                val existing = loadSecret(secretId).bind()
                loadOwnedWorker(userId, existing.workerId).bind()
                existing.toMetadataDto()
            }
        }

    override suspend fun getAssignedSecrets(
        workerId: Long,
    ): Either<WorkerCredentialServiceError, List<WorkerCredentialSecretDto>> =
        transactionScope.transaction {
            either {
                secretDao.getSecretsByWorkerId(workerId).map { it.toWorkerDto() }
            }
        }

    override suspend fun getAssignedSecret(
        secretId: Long,
    ): Either<WorkerCredentialServiceError, WorkerCredentialSecretDto> =
        transactionScope.transaction {
            either {
                loadSecret(secretId).bind().toWorkerDto()
            }
        }

    /**
     * Rejects a reference the shared rules refuse, before it is encrypted and echoed anywhere.
     *
     * The same rule validates a profile's `secretRef`, so one reference cannot pass on the write
     * surface that authors it and be refused on the other; a reference carrying control characters
     * would otherwise reach the logs and the model-visible error text.
     *
     * @receiver The Raise context used to propagate a typed failure.
     * @param reference Reference submitted with the request.
     */
    private fun Raise<WorkerCredentialServiceError>.ensureReferenceUsable(reference: String) {
        CredentialProfileRules.validateReference(reference)?.let { violation ->
            raise(WorkerCredentialServiceError.ValidationFailed(listOf(violation)))
        }
    }

    /**
     * Rejects a value the worker would refuse at attach time, before it is encrypted and stored.
     *
     * A stored value that no attach can use is invisible to the operator, who cannot read it back, so the
     * mistake is reported where it is typed instead of surfacing later as an unavailable credential.
     *
     * @receiver The Raise context used to propagate a typed failure.
     * @param value Value submitted with the request.
     */
    private fun Raise<WorkerCredentialServiceError>.ensureValueUsable(value: String) {
        CredentialSecretLimits.validateValue(value)?.let { violation ->
            raise(WorkerCredentialServiceError.ValidationFailed(listOf(violation)))
        }
    }

    /**
     * @param secretId Secret identifier.
     * @return Either a not-found failure or the stored secret.
     */
    private suspend fun loadSecret(secretId: Long): Either<WorkerCredentialServiceError, WorkerCredentialSecretEntity> =
        either {
            secretDao.getSecretById(secretId) ?: raise(WorkerCredentialServiceError.SecretNotFound(secretId))
        }

    /**
     * Loads a worker and verifies that the authenticated user owns it.
     *
     * @param userId Authenticated user.
     * @param workerId Worker whose certificate will receive the value.
     * @return Either a typed failure or the owned worker.
     */
    private suspend fun loadOwnedWorker(
        userId: Long,
        workerId: Long,
    ): Either<WorkerCredentialServiceError, eu.torvian.chatbot.server.data.entities.WorkerEntity> = either {
        val worker = withError({ error: WorkerError.NotFound ->
            WorkerCredentialServiceError.WorkerNotFound(error.workerId)
        }) {
            workerDao.getWorkerById(workerId).bind()
        }
        ensure(worker.ownerUserId == userId) { WorkerCredentialServiceError.Unauthorized(userId, workerId) }
        worker
    }

    /**
     * @receiver Persistence failure raised by the secret DAO.
     * @return The equivalent credential service error.
     */
    private fun WorkerCredentialPersistenceError.toServiceError(): WorkerCredentialServiceError = when (this) {
        is WorkerCredentialPersistenceError.NotFound -> WorkerCredentialServiceError.SecretNotFound(id)
        is WorkerCredentialPersistenceError.DuplicateSecretReference ->
            WorkerCredentialServiceError.DuplicateSecretReference(workerId, reference)
        is WorkerCredentialPersistenceError.DuplicateProfileName ->
            WorkerCredentialServiceError.DuplicateProfileName(workerId, name)
    }
}
