package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.either
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.WorkerCredentialSecretDao
import eu.torvian.chatbot.server.data.dao.error.WorkerCredentialPersistenceError
import eu.torvian.chatbot.server.data.entities.CreateWorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.UpdateWorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.tables.WorkerCredentialSecretTable
import eu.torvian.chatbot.server.data.tables.mappers.toWorkerCredentialSecretEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

/**
 * Exposed ORM implementation of [WorkerCredentialSecretDao].
 *
 * Every write stores ciphertext that the server cannot decrypt, so no method accepts or returns a
 * plaintext value and the worker binding is only ever written on insert.
 *
 * @property transactionScope Transaction boundary used for every statement.
 */
class WorkerCredentialSecretDaoExposed(
    private val transactionScope: TransactionScope,
) : WorkerCredentialSecretDao {

    override suspend fun createSecret(
        entity: CreateWorkerCredentialSecretEntity,
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialSecretEntity> =
        transactionScope.transaction {
            either {
                ensureUniqueReference(
                    workerId = entity.workerId,
                    reference = entity.reference,
                    excludingId = null
                ).bind()
                val now = Clock.System.now().toEpochMilliseconds()
                val insertedId = WorkerCredentialSecretTable.insertAndGetId { row ->
                    row[workerId] = entity.workerId
                    row[reference] = entity.reference
                    row[encryptedSecret] = entity.encryptedSecret
                    row[encryptedDek] = entity.encryptedDek
                    row[algorithm] = entity.algorithm
                    row[workerCertificateFingerprint] = entity.workerCertificateFingerprint
                    row[createdAt] = now
                    row[updatedAt] = now
                }.value

                loadOrFail(insertedId).bind()
            }
        }

    override suspend fun updateSecret(
        id: Long,
        entity: UpdateWorkerCredentialSecretEntity,
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialSecretEntity> =
        transactionScope.transaction {
            either {
                val existing = loadOrFail(id).bind()
                ensureUniqueReference(
                    workerId = existing.workerId,
                    reference = entity.reference,
                    excludingId = id
                ).bind()
                val now = Clock.System.now().toEpochMilliseconds()
                WorkerCredentialSecretTable.update({ WorkerCredentialSecretTable.id eq id }) { row ->
                    row[reference] = entity.reference
                    row[encryptedSecret] = entity.encryptedSecret
                    row[encryptedDek] = entity.encryptedDek
                    row[algorithm] = entity.algorithm
                    row[workerCertificateFingerprint] = entity.workerCertificateFingerprint
                    row[updatedAt] = now
                }

                loadOrFail(id).bind()
            }
        }

    override suspend fun deleteSecret(
        id: Long,
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialSecretEntity> =
        transactionScope.transaction {
            either {
                val existing = loadOrFail(id).bind()
                WorkerCredentialSecretTable.deleteWhere { WorkerCredentialSecretTable.id eq id }
                existing
            }
        }

    override suspend fun getSecretById(id: Long): WorkerCredentialSecretEntity? =
        transactionScope.transaction {
            WorkerCredentialSecretTable
                .selectAll()
                .where { WorkerCredentialSecretTable.id eq id }
                .singleOrNull()
                ?.toWorkerCredentialSecretEntity()
        }

    override suspend fun getSecretsByWorkerId(workerId: Long): List<WorkerCredentialSecretEntity> =
        transactionScope.transaction {
            WorkerCredentialSecretTable
                .selectAll()
                .where { WorkerCredentialSecretTable.workerId eq workerId }
                .map { it.toWorkerCredentialSecretEntity() }
        }

    override suspend fun getSecretByWorkerIdAndReference(
        workerId: Long,
        reference: String,
    ): WorkerCredentialSecretEntity? =
        transactionScope.transaction {
            WorkerCredentialSecretTable
                .selectAll()
                .where {
                    (WorkerCredentialSecretTable.workerId eq workerId) and
                            (WorkerCredentialSecretTable.reference eq reference)
                }
                .singleOrNull()
                ?.toWorkerCredentialSecretEntity()
        }

    /**
     * Ensures no other secret of the worker already uses [reference].
     *
     * @param workerId Worker the reference must be unique on.
     * @param reference Candidate reference.
     * @param excludingId Secret allowed to keep the reference, or null on insert.
     * @return Either a duplicate-reference failure or success.
     */
    private fun ensureUniqueReference(
        workerId: Long,
        reference: String,
        excludingId: Long?,
    ): Either<WorkerCredentialPersistenceError, Unit> = either {
        val collides = WorkerCredentialSecretTable
            .selectAll()
            .where {
                (WorkerCredentialSecretTable.workerId eq workerId) and
                        (WorkerCredentialSecretTable.reference eq reference) and
                        (WorkerCredentialSecretTable.id neq (excludingId ?: NO_EXCLUDED_ID))
            }
            .limit(1)
            .any()

        if (collides) {
            raise(
                WorkerCredentialPersistenceError.DuplicateSecretReference(
                    workerId = workerId,
                    reference = reference
                )
            )
        }
    }

    /**
     * @param id Secret identifier.
     * @return Either a not-found failure or the stored secret.
     */
    private fun loadOrFail(id: Long): Either<WorkerCredentialPersistenceError, WorkerCredentialSecretEntity> =
        either {
            val row = WorkerCredentialSecretTable
                .selectAll()
                .where { WorkerCredentialSecretTable.id eq id }
                .singleOrNull()
                ?: raise(WorkerCredentialPersistenceError.NotFound(id))
            row.toWorkerCredentialSecretEntity()
        }

    private companion object {
        /** Identifier no row can hold, used to disable the exclusion clause on insert. */
        const val NO_EXCLUDED_ID = -1L
    }
}
