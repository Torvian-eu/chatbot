package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.AgentRoleSpawnableRoleDao
import eu.torvian.chatbot.server.data.tables.AgentRoleSpawnableRolesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of [AgentRoleSpawnableRoleDao].
 *
 * The relation is unordered, so reads require no ordering clause; writes use delete-then-insert
 * inside the shared transaction so a failed replacement cannot partially apply.
 *
 * @property transactionScope Transaction wrapper for database access.
 */
class AgentRoleSpawnableRoleDaoExposed(
    private val transactionScope: TransactionScope
) : AgentRoleSpawnableRoleDao {
    override suspend fun getSpawnableRoleIdsForRole(sourceRoleId: Long): Set<Long> =
        transactionScope.transaction {
            AgentRoleSpawnableRolesTable
                .selectAll()
                .where { AgentRoleSpawnableRolesTable.sourceRoleId eq sourceRoleId }
                .map { it[AgentRoleSpawnableRolesTable.targetRoleId].value }
                .toSet()
        }

    override suspend fun getSpawnableRoleIdsForRoles(sourceRoleIds: List<Long>): Map<Long, Set<Long>> =
        transactionScope.transaction {
            if (sourceRoleIds.isEmpty()) return@transaction emptyMap()
            AgentRoleSpawnableRolesTable
                .selectAll()
                .where { AgentRoleSpawnableRolesTable.sourceRoleId inList sourceRoleIds }
                .groupBy { it[AgentRoleSpawnableRolesTable.sourceRoleId].value }
                .mapValues { (_, rows) -> rows.map { it[AgentRoleSpawnableRolesTable.targetRoleId].value }.toSet() }
        }

    override suspend fun replaceSpawnableRolesForRole(sourceRoleId: Long, targetIds: Set<Long>) {
        transactionScope.transaction {
            AgentRoleSpawnableRolesTable.deleteWhere {
                AgentRoleSpawnableRolesTable.sourceRoleId eq sourceRoleId
            }
            targetIds.forEach { targetRoleId ->
                AgentRoleSpawnableRolesTable.insert {
                    it[AgentRoleSpawnableRolesTable.sourceRoleId] = sourceRoleId
                    it[AgentRoleSpawnableRolesTable.targetRoleId] = targetRoleId
                }
            }
        }
    }
}
