package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.error.RoleError
import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.data.tables.RolesTable
import eu.torvian.chatbot.server.data.tables.mappers.toRoleEntity
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Exposed implementation of the [RoleDao].
 */
class RoleDaoExposed(
    private val transactionScope: TransactionScope
) : RoleDao {

    override suspend fun getAllRoles(): List<RoleEntity> =
        transactionScope.transaction {
            RolesTable.selectAll()
                .map { it.toRoleEntity() }
        }

    override suspend fun getRoleById(id: Long): Either<RoleError.RoleNotFound, RoleEntity> =
        transactionScope.transaction {
            RolesTable.selectAll().where { RolesTable.id eq id }
                .singleOrNull()
                ?.toRoleEntity()
                ?.right()
                ?: RoleError.RoleNotFound(id).left()
        }

    override suspend fun getRoleByName(name: String): Either<RoleError.RoleNotFoundByName, RoleEntity> =
        transactionScope.transaction {
            RolesTable.selectAll().where { RolesTable.name eq name }
                .singleOrNull()
                ?.toRoleEntity()
                ?.right()
                ?: RoleError.RoleNotFoundByName(name).left()
        }

    override suspend fun insertRole(
        name: String,
        description: String?
    ): Either<RoleError.RoleNameAlreadyExists, RoleEntity> =
        transactionScope.transaction {
            either {
                catch({
                    val insertStatement = RolesTable.insert {
                        it[RolesTable.name] = name
                        it[RolesTable.description] = description
                    }
                    insertStatement.resultedValues?.first()?.toRoleEntity()
                        ?: throw IllegalStateException("Failed to retrieve newly inserted role")
                }) { e: ExposedSQLException ->
                    ensure(!e.isUniqueConstraintViolation()) {
                        RoleError.RoleNameAlreadyExists(name)
                    }
                    throw e
                }
            }
        }

    override suspend fun updateRole(role: RoleEntity): Either<RoleError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    val updatedRowCount = RolesTable.update({ RolesTable.id eq role.id }) {
                        it[name] = role.name
                        it[description] = role.description
                    }
                    ensure(updatedRowCount != 0) { RoleError.RoleNotFound(role.id) }
                }) { e: ExposedSQLException ->
                    ensure(!e.isUniqueConstraintViolation()) {
                        RoleError.RoleNameAlreadyExists(role.name)
                    }
                    throw e
                }
            }
        }

    override suspend fun deleteRole(id: Long): Either<RoleError.RoleNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = RolesTable.deleteWhere { RolesTable.id eq id }
                ensure(deletedCount != 0) { RoleError.RoleNotFound(id) }
            }
        }
}
