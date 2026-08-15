package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.AgentRoleOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.tables.AgentRoleOwnersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [AgentRoleOwnershipDao].
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class AgentRoleOwnershipDaoExposed(
    private val transactionScope: TransactionScope
) : AgentRoleOwnershipDao {

    override suspend fun getOwner(roleId: Long): Either<GetOwnerError, Long> =
        transactionScope.transaction {
            AgentRoleOwnersTable
                .selectAll()
                .where { AgentRoleOwnersTable.roleId eq roleId }
                .singleOrNull()
                ?.let { it[AgentRoleOwnersTable.userId].value }
                ?.right()
                ?: GetOwnerError.ResourceNotFound(roleId.toString()).left()
        }

    override suspend fun setOwner(roleId: Long, userId: Long): Either<SetOwnerError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    AgentRoleOwnersTable.insert {
                        it[AgentRoleOwnersTable.roleId] = roleId
                        it[AgentRoleOwnersTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(SetOwnerError.ForeignKeyViolation(roleId.toString(), userId))

                        e.isUniqueConstraintViolation() ->
                            raise(SetOwnerError.AlreadyOwned)

                        else -> throw e
                    }
                }
            }
        }
}
