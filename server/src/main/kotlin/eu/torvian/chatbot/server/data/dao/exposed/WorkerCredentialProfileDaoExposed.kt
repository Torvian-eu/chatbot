package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.WorkerCredentialProfileDao
import eu.torvian.chatbot.server.data.dao.error.WorkerCredentialPersistenceError
import eu.torvian.chatbot.server.data.entities.CreateWorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.UpdateWorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.tables.WorkerCredentialProfileSignaturesTable
import eu.torvian.chatbot.server.data.tables.WorkerCredentialProfileTable
import eu.torvian.chatbot.server.data.tables.mappers.toWorkerCredentialProfileEntity
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

/**
 * Exposed ORM implementation of [WorkerCredentialProfileDao].
 *
 * The `(worker_id, name)` unique index is checked before writing so a collision surfaces as a logical
 * duplicate rather than a driver exception, which keeps the service layer's error mapping total.
 *
 * @property transactionScope Transaction boundary used for every statement.
 */
class WorkerCredentialProfileDaoExposed(
    private val transactionScope: TransactionScope,
) : WorkerCredentialProfileDao {

    override suspend fun createProfile(
        entity: CreateWorkerCredentialProfileEntity,
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialProfileEntity> =
        transactionScope.transaction {
            either {
                ensureUniqueName(workerId = entity.workerId, name = entity.name, excludingId = null).bind()
                val now = Clock.System.now().toEpochMilliseconds()
                val insertedId = WorkerCredentialProfileTable.insertAndGetId { row ->
                    row[workerId] = entity.workerId
                    row[name] = entity.name
                    row[description] = entity.description
                    row[secretRef] = entity.secretRef
                    row[command] = entity.command
                    row[argsJson] = Json.encodeToString(entity.args)
                    row[channel] = entity.channel.name
                    row[envName] = entity.envName
                    row[fileName] = entity.fileName
                    row[askpassUsername] = entity.askpassUsername
                    row[environmentJson] = Json.encodeToString(entity.environmentVariables)
                    row[askpassVariablesJson] = Json.encodeToString(entity.askpassVariables)
                    row[isEnabled] = entity.isEnabled
                    row[createdAt] = now
                    row[updatedAt] = now
                }.value

                loadOrFail(insertedId).bind()
            }
        }

    override suspend fun updateProfile(
        id: Long,
        entity: UpdateWorkerCredentialProfileEntity,
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialProfileEntity> =
        transactionScope.transaction {
            either {
                ensure(profileExists(id)) { WorkerCredentialPersistenceError.NotFound(id) }
                ensureUniqueName(workerId = entity.workerId, name = entity.name, excludingId = id).bind()
                val now = Clock.System.now().toEpochMilliseconds()
                WorkerCredentialProfileTable.update({ WorkerCredentialProfileTable.id eq id }) { row ->
                    row[workerId] = entity.workerId
                    row[name] = entity.name
                    row[description] = entity.description
                    row[secretRef] = entity.secretRef
                    row[command] = entity.command
                    row[argsJson] = Json.encodeToString(entity.args)
                    row[channel] = entity.channel.name
                    row[envName] = entity.envName
                    row[fileName] = entity.fileName
                    row[askpassUsername] = entity.askpassUsername
                    row[environmentJson] = Json.encodeToString(entity.environmentVariables)
                    row[askpassVariablesJson] = Json.encodeToString(entity.askpassVariables)
                    row[isEnabled] = entity.isEnabled
                    row[updatedAt] = now
                }

                loadOrFail(id).bind()
            }
        }

    override suspend fun deleteProfile(
        id: Long,
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialProfileEntity> =
        transactionScope.transaction {
            either {
                val existing = loadOrFail(id).bind()
                // Signatures are removed explicitly as well as by the foreign key, so the delete also
                // works on a connection that does not enforce foreign keys.
                WorkerCredentialProfileSignaturesTable.deleteWhere {
                    WorkerCredentialProfileSignaturesTable.profileId eq id
                }
                WorkerCredentialProfileTable.deleteWhere { WorkerCredentialProfileTable.id eq id }
                existing
            }
        }

    override suspend fun getProfileById(id: Long): WorkerCredentialProfileEntity? =
        transactionScope.transaction {
            WorkerCredentialProfileTable
                .selectAll()
                .where { WorkerCredentialProfileTable.id eq id }
                .singleOrNull()
                ?.toWorkerCredentialProfileEntity()
        }

    override suspend fun getProfilesByWorkerId(workerId: Long): List<WorkerCredentialProfileEntity> =
        transactionScope.transaction {
            WorkerCredentialProfileTable
                .selectAll()
                .where { WorkerCredentialProfileTable.workerId eq workerId }
                .map { it.toWorkerCredentialProfileEntity() }
        }

    override suspend fun getEnabledProfilesByWorkerId(workerId: Long): List<WorkerCredentialProfileEntity> =
        transactionScope.transaction {
            WorkerCredentialProfileTable
                .selectAll()
                .where {
                    (WorkerCredentialProfileTable.workerId eq workerId) and
                        (WorkerCredentialProfileTable.isEnabled eq true)
                }
                .map { it.toWorkerCredentialProfileEntity() }
        }

    /**
     * @param id Profile identifier.
     * @return Whether a profile row with that identifier exists.
     */
    private fun profileExists(id: Long): Boolean =
        WorkerCredentialProfileTable
            .selectAll()
            .where { WorkerCredentialProfileTable.id eq id }
            .limit(1)
            .any()

    /**
     * Ensures no other profile of the worker already uses [name].
     *
     * @param workerId Worker the name must be unique on.
     * @param name Candidate name.
     * @param excludingId Profile allowed to keep the name, or null on insert.
     * @return Either a duplicate-name failure or success.
     */
    private fun ensureUniqueName(
        workerId: Long,
        name: String,
        excludingId: Long?,
    ): Either<WorkerCredentialPersistenceError, Unit> = either {
        val collides = WorkerCredentialProfileTable
            .selectAll()
            .where {
                (WorkerCredentialProfileTable.workerId eq workerId) and
                    (WorkerCredentialProfileTable.name eq name) and
                    (WorkerCredentialProfileTable.id neq (excludingId ?: NO_EXCLUDED_ID))
            }
            .limit(1)
            .any()

        if (collides) {
            raise(WorkerCredentialPersistenceError.DuplicateProfileName(workerId = workerId, name = name))
        }
    }

    /**
     * @param id Profile identifier.
     * @return Either a not-found failure or the stored profile.
     */
    private fun loadOrFail(id: Long): Either<WorkerCredentialPersistenceError, WorkerCredentialProfileEntity> =
        either {
            val row = WorkerCredentialProfileTable
                .selectAll()
                .where { WorkerCredentialProfileTable.id eq id }
                .singleOrNull()
                ?: raise(WorkerCredentialPersistenceError.NotFound(id))
            row.toWorkerCredentialProfileEntity()
        }

    private companion object {
        /** Identifier no row can hold, used to disable the exclusion clause on insert. */
        const val NO_EXCLUDED_ID = -1L
    }
}
