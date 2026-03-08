package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.PermissionDao
import eu.torvian.chatbot.server.data.dao.error.PermissionError
import eu.torvian.chatbot.server.data.entities.PermissionEntity
import eu.torvian.chatbot.server.data.tables.PermissionsTable
import eu.torvian.chatbot.server.data.tables.RolePermissionsTable
import eu.torvian.chatbot.server.data.tables.mappers.toPermissionEntity
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [PermissionDao].
 */
class PermissionDaoExposed(
    private val transactionScope: TransactionScope
) : PermissionDao {

    override suspend fun getAllPermissions(): List<PermissionEntity> =
        transactionScope.transaction {
            PermissionsTable.selectAll()
                .map { it.toPermissionEntity() }
        }

    override suspend fun getPermissionById(id: Long): Either<PermissionError.PermissionNotFound, PermissionEntity> =
        transactionScope.transaction {
            PermissionsTable.selectAll().where { PermissionsTable.id eq id }
                .singleOrNull()
                ?.toPermissionEntity()
                ?.right()
                ?: PermissionError.PermissionNotFound(id).left()
        }

    override suspend fun getPermissionByActionAndSubject(
        action: String,
        subject: String
    ): Either<PermissionError.PermissionNotFound, PermissionEntity> =
        transactionScope.transaction {
            PermissionsTable.selectAll().where {
                (PermissionsTable.action eq action) and (PermissionsTable.subject eq subject)
            }
                .singleOrNull()
                ?.toPermissionEntity()
                ?.right()
                ?: PermissionError.PermissionNotFound(0).left()
        }

    override suspend fun insertPermission(
        action: String,
        subject: String
    ): Either<PermissionError.PermissionAlreadyExists, PermissionEntity> =
        transactionScope.transaction {
            either {
                catch({
                    val insertStatement = PermissionsTable.insert {
                        it[PermissionsTable.action] = action
                        it[PermissionsTable.subject] = subject
                    }
                    insertStatement.resultedValues?.first()?.toPermissionEntity()
                        ?: throw IllegalStateException("Failed to retrieve newly inserted permission")
                }) { e: ExposedSQLException ->
                    ensure(!e.isUniqueConstraintViolation()) {
                        PermissionError.PermissionAlreadyExists(action, subject)
                    }
                    throw e
                }
            }
        }

    override suspend fun deletePermission(id: Long): Either<PermissionError.PermissionNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = PermissionsTable.deleteWhere { PermissionsTable.id eq id }
                ensure(deletedCount != 0) { PermissionError.PermissionNotFound(id) }
            }
        }

    override suspend fun getPermissionsByRoleId(roleId: Long): List<PermissionEntity> =
        transactionScope.transaction {
            PermissionsTable
                .join(
                    RolePermissionsTable,
                    JoinType.INNER,
                    additionalConstraint = { PermissionsTable.id eq RolePermissionsTable.permissionId }
                )
                .selectAll()
                .where { RolePermissionsTable.roleId eq roleId }
                .orderBy(PermissionsTable.action to SortOrder.ASC, PermissionsTable.subject to SortOrder.ASC)
                .map { it.toPermissionEntity() }
        }
}
