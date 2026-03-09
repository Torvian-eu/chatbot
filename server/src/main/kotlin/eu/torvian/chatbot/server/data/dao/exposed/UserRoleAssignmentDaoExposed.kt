package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.dao.error.UserRoleAssignmentError
import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.data.tables.RolesTable
import eu.torvian.chatbot.server.data.tables.UserRoleAssignmentsTable
import eu.torvian.chatbot.server.data.tables.mappers.toRoleEntity
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [UserRoleAssignmentDao].
 */
class UserRoleAssignmentDaoExposed(
    private val transactionScope: TransactionScope
) : UserRoleAssignmentDao {

    override suspend fun getRolesByUserId(userId: Long): List<RoleEntity> =
        transactionScope.transaction {
            RolesTable
                .join(
                    UserRoleAssignmentsTable,
                    JoinType.INNER,
                    additionalConstraint = { RolesTable.id eq UserRoleAssignmentsTable.roleId }
                )
                .selectAll()
                .where { UserRoleAssignmentsTable.userId eq userId }
                .orderBy(RolesTable.name to SortOrder.ASC)
                .map { it.toRoleEntity() }
        }

    override suspend fun getUserIdsByRoleId(roleId: Long): List<Long> =
        transactionScope.transaction {
            UserRoleAssignmentsTable
                .selectAll()
                .where { UserRoleAssignmentsTable.roleId eq roleId }
                .map { it[UserRoleAssignmentsTable.userId].value }
        }

    override suspend fun assignRoleToUser(
        userId: Long,
        roleId: Long
    ): Either<UserRoleAssignmentError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    UserRoleAssignmentsTable.insert {
                        it[UserRoleAssignmentsTable.userId] = userId
                        it[UserRoleAssignmentsTable.roleId] = roleId
                        it[assignedAt] = System.currentTimeMillis()
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isUniqueConstraintViolation() ->
                            raise(UserRoleAssignmentError.AssignmentAlreadyExists(userId, roleId))

                        e.isForeignKeyViolation() ->
                            raise(
                                UserRoleAssignmentError.ForeignKeyViolation(
                                    "User ID $userId or Role ID $roleId does not exist"
                                )
                            )

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun revokeRoleFromUser(
        userId: Long,
        roleId: Long
    ): Either<UserRoleAssignmentError.AssignmentNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = UserRoleAssignmentsTable.deleteWhere {
                    (UserRoleAssignmentsTable.userId eq userId) and
                            (UserRoleAssignmentsTable.roleId eq roleId)
                }
                ensure(deletedCount != 0) {
                    UserRoleAssignmentError.AssignmentNotFound(userId, roleId)
                }
            }
        }

    override suspend fun hasRole(userId: Long, roleId: Long): Boolean =
        transactionScope.transaction {
            UserRoleAssignmentsTable
                .selectAll()
                .where {
                    (UserRoleAssignmentsTable.userId eq userId) and
                            (UserRoleAssignmentsTable.roleId eq roleId)
                }
                .singleOrNull() != null
        }
}

