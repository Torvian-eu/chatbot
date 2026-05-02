package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.WorkerAuthChallengeEntity
import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.data.tables.WorkerAuthChallengesTable
import eu.torvian.chatbot.server.data.tables.WorkersTable
import eu.torvian.chatbot.server.data.tables.mappers.toWorkerAuthChallengeEntity
import eu.torvian.chatbot.server.data.tables.mappers.toWorkerEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.serialization.json.Json

/**
 * Exposed implementation of [WorkerDao].
 *
 * @property transactionScope Transaction boundary abstraction used for all DB operations.
 */
class WorkerDaoExposed(
    private val transactionScope: TransactionScope
) : WorkerDao {
    override suspend fun createWorker(
        ownerUserId: Long,
        workerUid: String,
        displayName: String,
        certificatePem: String,
        certificateFingerprint: String,
        allowedScopes: List<String>
    ): Either<WorkerError, WorkerEntity> =
        transactionScope.transaction {
            either {
                catch({
                    val now = System.currentTimeMillis()
                    // Scope lists are still stored in the existing JSON column, but the DAO keeps the list form.
                    val allowedScopesJson = Json.encodeToString(allowedScopes.distinct())
                    val inserted = WorkersTable.insert {
                        it[WorkersTable.ownerUserId] = ownerUserId
                        it[WorkersTable.workerUid] = workerUid
                        it[WorkersTable.displayName] = displayName
                        it[WorkersTable.certificatePem] = certificatePem
                        it[WorkersTable.certificateFingerprint] = certificateFingerprint
                        it[WorkersTable.allowedScopesJson] = allowedScopesJson
                        it[WorkersTable.createdAt] = now
                    }
                    inserted.resultedValues?.first()?.toWorkerEntity()
                        ?: throw IllegalStateException("Failed to create worker")
                }) { e: ExposedSQLException ->
                    if (e.isUniqueConstraintViolation()) {
                        when {
                            e.message?.contains("worker_uid", ignoreCase = true) == true -> raise(WorkerError.DuplicateWorkerUid(workerUid))
                            else -> raise(WorkerError.DuplicateCertificateFingerprint(certificateFingerprint))
                        }
                    } else {
                        throw e
                    }
                }
            }
        }

    override suspend fun getWorkerById(workerId: Long): Either<WorkerError.NotFound, WorkerEntity> =
        transactionScope.transaction {
            WorkersTable.selectAll().where { WorkersTable.id eq workerId }
                .singleOrNull()
                ?.toWorkerEntity()
                ?.right()
                ?: WorkerError.NotFound(workerId).left()
        }

    override suspend fun getWorkerByUid(workerUid: String): Either<WorkerError.UidNotFound, WorkerEntity> =
        transactionScope.transaction {
            WorkersTable.selectAll().where { WorkersTable.workerUid eq workerUid }
                .singleOrNull()
                ?.toWorkerEntity()
                ?.right()
                ?: WorkerError.UidNotFound(workerUid).left()
        }

    override suspend fun getWorkerByFingerprint(certificateFingerprint: String): WorkerEntity? =
        transactionScope.transaction {
            WorkersTable.selectAll().where { WorkersTable.certificateFingerprint eq certificateFingerprint }
                .singleOrNull()
                ?.toWorkerEntity()
        }

    override suspend fun updateLastSeen(workerId: Long, lastSeenAtEpochMs: Long): Either<WorkerError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val updated = WorkersTable.update({ WorkersTable.id eq workerId }) {
                    it[WorkersTable.lastSeenAt] = lastSeenAtEpochMs
                }
                ensure(updated > 0) { WorkerError.NotFound(workerId) }
            }
        }

    override suspend fun updateWorker(
        id: Long,
        displayName: String,
        allowedScopes: List<String>
    ): Either<WorkerError.NotFound, WorkerEntity> =
        transactionScope.transaction {
            either {
                val allowedScopesJson = Json.encodeToString(allowedScopes.distinct())
                val updated = WorkersTable.update({ WorkersTable.id eq id }) {
                    it[WorkersTable.displayName] = displayName
                    it[WorkersTable.allowedScopesJson] = allowedScopesJson
                }
                ensure(updated > 0) { WorkerError.NotFound(id) }

                // Fetch and return the updated entity
                WorkersTable.selectAll().where { WorkersTable.id eq id }
                    .singleOrNull()
                    ?.toWorkerEntity()
                    ?: throw IllegalStateException("Worker not found after update")
            }
        }

    override suspend fun deleteWorker(id: Long): Either<WorkerError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val deleted = WorkersTable.deleteWhere { WorkersTable.id eq id }
                ensure(deleted > 0) { WorkerError.NotFound(id) }
            }
        }

    override suspend fun createChallenge(
        workerId: Long,
        challengeId: String,
        challenge: String,
        expiresAtEpochMs: Long
    ): WorkerAuthChallengeEntity = transactionScope.transaction {
        val now = System.currentTimeMillis()
        val inserted = WorkerAuthChallengesTable.insert {
            it[WorkerAuthChallengesTable.challengeId] = challengeId
            it[WorkerAuthChallengesTable.workerId] = workerId
            it[WorkerAuthChallengesTable.challenge] = challenge
            it[WorkerAuthChallengesTable.expiresAt] = expiresAtEpochMs
            it[WorkerAuthChallengesTable.createdAt] = now
            it[WorkerAuthChallengesTable.consumedAt] = null
        }
        inserted.resultedValues?.first()?.toWorkerAuthChallengeEntity()
            ?: throw IllegalStateException("Failed to create challenge")
    }

    override suspend fun getChallenge(
        workerId: Long,
        challengeId: String,
        nowEpochMs: Long
    ): Either<WorkerError.InvalidChallenge, WorkerAuthChallengeEntity> = transactionScope.transaction {
        WorkerAuthChallengesTable.selectAll().where {
            (WorkerAuthChallengesTable.workerId eq workerId) and
                (WorkerAuthChallengesTable.challengeId eq challengeId) and
                (WorkerAuthChallengesTable.expiresAt greaterEq nowEpochMs) and
                WorkerAuthChallengesTable.consumedAt.isNull()
        }.singleOrNull()
            ?.toWorkerAuthChallengeEntity()
            ?.right()
            ?: WorkerError.InvalidChallenge(challengeId).left()
    }

    override suspend fun consumeChallenge(challengeId: String): Either<WorkerError.InvalidChallenge, Unit> =
        transactionScope.transaction {
            either {
                val updated = WorkerAuthChallengesTable.update(
                    where = {
                        (WorkerAuthChallengesTable.challengeId eq challengeId) and WorkerAuthChallengesTable.consumedAt.isNull()
                    }
                ) {
                    it[WorkerAuthChallengesTable.consumedAt] = System.currentTimeMillis()
                }
                ensure(updated > 0) { WorkerError.InvalidChallenge(challengeId) }
            }
        }

    override suspend fun getWorkersByOwnerId(ownerUserId: Long): List<WorkerEntity> =
        transactionScope.transaction {
            WorkersTable.selectAll().where { WorkersTable.ownerUserId eq ownerUserId }
                .map { it.toWorkerEntity() }
        }
}

