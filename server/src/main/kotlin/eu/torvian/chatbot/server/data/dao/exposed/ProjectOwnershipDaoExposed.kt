package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.ProjectOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.tables.ProjectOwnersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [ProjectOwnershipDao].
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class ProjectOwnershipDaoExposed(
    private val transactionScope: TransactionScope
) : ProjectOwnershipDao {

    override suspend fun getOwner(projectId: Long): Either<GetOwnerError, Long> =
        transactionScope.transaction {
            ProjectOwnersTable
                .selectAll()
                .where { ProjectOwnersTable.projectId eq projectId }
                .singleOrNull()
                ?.let { it[ProjectOwnersTable.userId].value }
                ?.right()
                ?: GetOwnerError.ResourceNotFound(projectId.toString()).left()
        }

    override suspend fun setOwner(projectId: Long, userId: Long): Either<SetOwnerError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    ProjectOwnersTable.insert {
                        it[ProjectOwnersTable.projectId] = projectId
                        it[ProjectOwnersTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(SetOwnerError.ForeignKeyViolation(projectId.toString(), userId))

                        e.isUniqueConstraintViolation() ->
                            raise(SetOwnerError.AlreadyOwned)

                        else -> throw e
                    }
                }
            }
        }
}