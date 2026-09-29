package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.InstructionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.tables.InstructionOwnersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [InstructionOwnershipDao].
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class InstructionOwnershipDaoExposed(
    private val transactionScope: TransactionScope
) : InstructionOwnershipDao {

    override suspend fun getOwner(instructionId: Long): Either<GetOwnerError, Long> =
        transactionScope.transaction {
            InstructionOwnersTable
                .selectAll()
                .where { InstructionOwnersTable.instructionId eq instructionId }
                .singleOrNull()
                ?.let { it[InstructionOwnersTable.userId].value }
                ?.right()
                ?: GetOwnerError.ResourceNotFound(instructionId.toString()).left()
        }

    override suspend fun setOwner(instructionId: Long, userId: Long): Either<SetOwnerError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    InstructionOwnersTable.insert {
                        it[InstructionOwnersTable.instructionId] = instructionId
                        it[InstructionOwnersTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(SetOwnerError.ForeignKeyViolation(instructionId.toString(), userId))

                        e.isUniqueConstraintViolation() ->
                            raise(SetOwnerError.AlreadyOwned)

                        else -> throw e
                    }
                }
            }
        }
}
