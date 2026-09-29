package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.data.dao.error.InstructionError
import eu.torvian.chatbot.server.data.entities.InstructionEntity
import eu.torvian.chatbot.server.data.tables.InstructionOwnersTable
import eu.torvian.chatbot.server.data.tables.InstructionTable
import eu.torvian.chatbot.server.data.tables.mappers.toInstructionEntity
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of the [InstructionDao].
 *
 * `created_at`/`updated_at` are managed here (set on insert, `updated_at` advanced on update) so no
 * caller can forget them. The owner lives in `instruction_owners` and is loaded through
 * [InstructionOwnershipDao], so this class stays a plain table projection.
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class InstructionDaoExposed(
    private val transactionScope: TransactionScope
) : InstructionDao {

    override suspend fun getInstructionsByIds(ids: List<Long>): List<InstructionEntity> =
        transactionScope.transaction {
            if (ids.isEmpty()) return@transaction emptyList()
            val entitiesById = InstructionTable
                .selectAll()
                .where { InstructionTable.id inList ids }
                .map { it.toInstructionEntity() }
                .associateBy { it.id }
            // SQL does not guarantee IN-list order; restore the caller's order where present.
            ids.mapNotNull { entitiesById[it] }
        }

    override suspend fun getInstructionsByIdsForUser(userId: Long, ids: List<Long>): List<InstructionEntity> =
        transactionScope.transaction {
            if (ids.isEmpty()) return@transaction emptyList()
            val entitiesById = InstructionTable
                .join(
                    InstructionOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { InstructionTable.id eq InstructionOwnersTable.instructionId }
                )
                .selectAll()
                .where {
                    (InstructionOwnersTable.userId eq userId) and (InstructionTable.id inList ids)
                }
                .map { it.toInstructionEntity() }
                .associateBy { it.id }
            // SQL does not guarantee IN-list order; restore the caller's order where present.
            ids.mapNotNull { entitiesById[it] }
        }

    override suspend fun getAllInstructionsForUser(userId: Long): List<InstructionEntity> =
        transactionScope.transaction {
            InstructionTable
                .join(
                    InstructionOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { InstructionTable.id eq InstructionOwnersTable.instructionId }
                )
                .selectAll()
                .where { InstructionOwnersTable.userId eq userId }
                // Deterministic listing: id ascending (shared by every library listing).
                .orderBy(InstructionTable.id)
                .map { it.toInstructionEntity() }
        }

    override suspend fun getInstructionById(id: Long): Either<InstructionError.NotFound, InstructionEntity> =
        transactionScope.transaction {
            either {
                val entity = InstructionTable.selectAll().where { InstructionTable.id eq id }
                    .singleOrNull()
                    ?.toInstructionEntity()
                ensure(entity != null) { InstructionError.NotFound(id) }
                entity
            }
        }

    override suspend fun insertInstruction(
        type: String,
        name: String,
        message: String?,
        custom: String?
    ): InstructionEntity =
        transactionScope.transaction {
            val now = System.currentTimeMillis()
            val id = InstructionTable.insert {
                it[InstructionTable.type] = type
                it[InstructionTable.name] = name
                it[InstructionTable.message] = message
                it[InstructionTable.custom] = custom
                it[createdAt] = now
                it[updatedAt] = now
            } get InstructionTable.id
            InstructionEntity(
                id = id.value,
                type = type,
                name = name,
                message = message,
                custom = custom,
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(now),
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(now)
            )
        }

    override suspend fun updateInstruction(
        instruction: InstructionEntity
    ): Either<InstructionError.NotFound, InstructionEntity> =
        transactionScope.transaction {
            val updatedAt = System.currentTimeMillis()
            val updated = InstructionTable.update({ InstructionTable.id eq instruction.id }) {
                it[type] = instruction.type
                it[name] = instruction.name
                it[message] = instruction.message
                it[custom] = instruction.custom
                it[InstructionTable.updatedAt] = updatedAt
            }
            if (updated > 0) {
                // `created_at` is preserved by the update; echo the row as it now stands so callers
                // can map the persisted content without a second read.
                instruction.copy(updatedAt = kotlin.time.Instant.fromEpochMilliseconds(updatedAt)).right()
            } else {
                InstructionError.NotFound(instruction.id).left()
            }
        }

    override suspend fun deleteInstruction(id: Long): Either<InstructionError, Unit> =
        transactionScope.transaction {
            either {
                val deleted =
                    catch({ InstructionTable.deleteWhere { InstructionTable.id eq id } }) { e: ExposedSQLException ->
                        when {
                            // The role link is the only restricting reference to a row, so storage can
                            // refuse this delete for one reason: a role still links the instruction.
                            e.isForeignKeyViolation() ->
                                raise(InstructionError.ForeignKeyViolation(e.message ?: "Foreign key violation"))

                            else -> throw e
                        }
                    }
                ensure(deleted > 0) { InstructionError.NotFound(id) }
            }
        }
}
