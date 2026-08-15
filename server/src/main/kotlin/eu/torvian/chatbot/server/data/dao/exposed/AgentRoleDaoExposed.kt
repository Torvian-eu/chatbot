package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.error.AgentRoleError
import eu.torvian.chatbot.server.data.entities.AgentRoleEntity
import eu.torvian.chatbot.server.data.tables.AgentRoleOwnersTable
import eu.torvian.chatbot.server.data.tables.AgentRoleTable
import eu.torvian.chatbot.server.data.tables.mappers.toAgentRoleEntity
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of the [AgentRoleDao].
 *
 * All operations are single-row reads/writes; the JSON `instructions_json` column is passed through
 * verbatim so serialization stays at the service boundary. The role's tool ids live in the separate
 * `agent_role_tools` join table and are managed through [AgentRoleToolDao].
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class AgentRoleDaoExposed(
    private val transactionScope: TransactionScope
) : AgentRoleDao {

    override suspend fun getAllRoles(): List<AgentRoleEntity> =
        transactionScope.transaction {
            AgentRoleTable.selectAll()
                .map { it.toAgentRoleEntity() }
        }

    override suspend fun getAllRolesForUser(userId: Long): List<AgentRoleEntity> =
        transactionScope.transaction {
            AgentRoleTable
                .join(
                    AgentRoleOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { AgentRoleTable.id eq AgentRoleOwnersTable.roleId }
                )
                .selectAll()
                .where { AgentRoleOwnersTable.userId eq userId }
                .map { it.toAgentRoleEntity() }
        }

    override suspend fun getRoleById(id: Long): Either<AgentRoleError.NotFound, AgentRoleEntity> =
        transactionScope.transaction {
            AgentRoleTable.selectAll().where { AgentRoleTable.id eq id }
                .singleOrNull()
                ?.toAgentRoleEntity()
                ?.right()
                ?: AgentRoleError.NotFound(id).left()
        }

    override suspend fun getRoleByNameForUser(
        userId: Long,
        name: String
    ): Either<AgentRoleError.NotFoundByName, AgentRoleEntity> =
        transactionScope.transaction {
            AgentRoleTable
                .join(
                    AgentRoleOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { AgentRoleTable.id eq AgentRoleOwnersTable.roleId }
                )
                .selectAll()
                .where { (AgentRoleOwnersTable.userId eq userId) and (AgentRoleTable.name eq name) }
                .singleOrNull()
                ?.toAgentRoleEntity()
                ?.right()
                ?: AgentRoleError.NotFoundByName(name).left()
        }

    override suspend fun roleNameExistsForUser(userId: Long, name: String): Boolean =
        transactionScope.transaction {
            AgentRoleTable
                .join(
                    AgentRoleOwnersTable,
                    JoinType.INNER,
                    additionalConstraint = { AgentRoleTable.id eq AgentRoleOwnersTable.roleId }
                )
                .selectAll()
                .where { (AgentRoleOwnersTable.userId eq userId) and (AgentRoleTable.name eq name) }
                .count() > 0
        }

    override suspend fun insertRole(
        name: String,
        displayName: String?,
        description: String,
        modelId: Long?,
        modelSettingsId: Long?,
        instructionsJson: String
    ): AgentRoleEntity =
        transactionScope.transaction {
            val now = System.currentTimeMillis()
            val insertStatement = AgentRoleTable.insert {
                it[AgentRoleTable.name] = name
                it[AgentRoleTable.displayName] = displayName
                it[AgentRoleTable.description] = description
                it[AgentRoleTable.modelId] = modelId
                it[AgentRoleTable.modelSettingsId] = modelSettingsId
                it[AgentRoleTable.instructionsJson] = instructionsJson
                it[AgentRoleTable.createdAt] = now
                it[AgentRoleTable.updatedAt] = now
            }
            insertStatement.resultedValues?.first()?.toAgentRoleEntity()
                ?: throw IllegalStateException("Failed to retrieve newly inserted agent role")
        }

    override suspend fun updateRole(role: AgentRoleEntity): Either<AgentRoleError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val updatedRowCount = AgentRoleTable.update({ AgentRoleTable.id eq role.id }) {
                    it[AgentRoleTable.name] = role.name
                    it[AgentRoleTable.displayName] = role.displayName
                    it[AgentRoleTable.description] = role.description
                    it[AgentRoleTable.modelId] = role.modelId
                    it[AgentRoleTable.modelSettingsId] = role.modelSettingsId
                    it[AgentRoleTable.instructionsJson] = role.instructionsJson
                    it[AgentRoleTable.updatedAt] = System.currentTimeMillis()
                }
                ensure(updatedRowCount != 0) { AgentRoleError.NotFound(role.id) }
            }
        }

    override suspend fun deleteRole(id: Long): Either<AgentRoleError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = AgentRoleTable.deleteWhere { AgentRoleTable.id eq id }
                ensure(deletedCount != 0) { AgentRoleError.NotFound(id) }
            }
        }
}
