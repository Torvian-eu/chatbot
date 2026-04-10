package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.worker.Worker
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.WorkerAuthChallengeEntity
import eu.torvian.chatbot.server.data.tables.WorkerAuthChallengesTable
import eu.torvian.chatbot.server.data.tables.WorkersTable
import eu.torvian.chatbot.server.data.tables.mappers.toWorkerAuthChallengeEntity
import eu.torvian.chatbot.server.data.tables.mappers.toWorker
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
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
        displayName: String,
        certificatePem: String,
        certificateFingerprint: String,
        allowedScopes: List<String>
    ): Either<WorkerError.DuplicateCertificateFingerprint, Worker> =
        transactionScope.transaction {
            either {
                catch({
                    val now = System.currentTimeMillis()
                    // Scope lists are still stored in the existing JSON column, but the DAO keeps the list form.
                    val allowedScopesJson = Json.encodeToString(allowedScopes.distinct())
                    val inserted = WorkersTable.insert {
                        it[WorkersTable.ownerUserId] = ownerUserId
                        it[WorkersTable.displayName] = displayName
                        it[WorkersTable.certificatePem] = certificatePem
                        it[WorkersTable.certificateFingerprint] = certificateFingerprint
                        it[WorkersTable.allowedScopesJson] = allowedScopesJson
                        it[WorkersTable.createdAt] = now
                    }
                    inserted.resultedValues?.first()?.toWorker()
                        ?: throw IllegalStateException("Failed to create worker")
                }) { e: ExposedSQLException ->
                    ensure(
                        !e.message.orEmpty().contains("workers_certificate_fingerprint_unique", ignoreCase = true)
                    ) { WorkerError.DuplicateCertificateFingerprint(certificateFingerprint) }
                    throw e
                }
            }
        }

    override suspend fun getWorkerById(workerId: Long): Either<WorkerError.NotFound, Worker> =
        transactionScope.transaction {
            WorkersTable.selectAll().where { WorkersTable.id eq workerId }
                .singleOrNull()
                ?.toWorker()
                ?.right()
                ?: WorkerError.NotFound(workerId).left()
        }

    override suspend fun getWorkerByFingerprint(certificateFingerprint: String): Worker? =
        transactionScope.transaction {
            WorkersTable.selectAll().where { WorkersTable.certificateFingerprint eq certificateFingerprint }
                .singleOrNull()
                ?.toWorker()
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
}


