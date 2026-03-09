package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.RolePermissionDao
import eu.torvian.chatbot.server.data.dao.error.RolePermissionError
import eu.torvian.chatbot.server.data.tables.RolePermissionsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert

/**
 * Exposed implementation of the [RolePermissionDao].
 */
class RolePermissionDaoExposed(
    private val transactionScope: TransactionScope
) : RolePermissionDao {

    override suspend fun assignPermissionToRole(
        roleId: Long,
        permissionId: Long
    ): Either<RolePermissionError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    RolePermissionsTable.insert {
                        it[RolePermissionsTable.roleId] = roleId
                        it[RolePermissionsTable.permissionId] = permissionId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isUniqueConstraintViolation() ->
                            raise(RolePermissionError.AssignmentAlreadyExists(roleId, permissionId))

                        e.isForeignKeyViolation() ->
                            raise(
                                RolePermissionError.ForeignKeyViolation(
                                    "Role ID $roleId or Permission ID $permissionId does not exist"
                                )
                            )

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun revokePermissionFromRole(
        roleId: Long,
        permissionId: Long
    ): Either<RolePermissionError.AssignmentNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = RolePermissionsTable.deleteWhere {
                    (RolePermissionsTable.roleId eq roleId) and
                            (RolePermissionsTable.permissionId eq permissionId)
                }
                ensure(deletedCount != 0) {
                    RolePermissionError.AssignmentNotFound(roleId, permissionId)
                }
            }
        }
}
