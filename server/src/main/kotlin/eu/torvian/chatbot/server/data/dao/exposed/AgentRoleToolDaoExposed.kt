package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.AgentRoleToolDao
import eu.torvian.chatbot.server.data.tables.AgentRoleToolsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [AgentRoleToolDao].
 *
 * Operates on the `agent_role_tools` join table. Reads return tool ids as sets (the relation is
 * deliberately unordered); writes are full replacements (delete + batch insert) because the update
 * flow already rewrites the role's whole tool set.
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class AgentRoleToolDaoExposed(
    private val transactionScope: TransactionScope
) : AgentRoleToolDao {

    override suspend fun getToolsForRole(roleId: Long): Set<Long> =
        transactionScope.transaction {
            AgentRoleToolsTable
                .selectAll()
                .where { AgentRoleToolsTable.roleId eq roleId }
                .map { it[AgentRoleToolsTable.toolDefinitionId].value }
                .toSet()
        }

    override suspend fun getToolsForRoles(roleIds: List<Long>): Map<Long, Set<Long>> =
        transactionScope.transaction {
            if (roleIds.isEmpty()) {
                return@transaction emptyMap()
            }
            AgentRoleToolsTable
                .selectAll()
                .where { AgentRoleToolsTable.roleId inList roleIds }
                .groupBy { it[AgentRoleToolsTable.roleId].value }
                .mapValues { (_, rows) ->
                    rows.map { it[AgentRoleToolsTable.toolDefinitionId].value }.toSet()
                }
        }

    override suspend fun replaceToolsForRole(roleId: Long, toolIds: Set<Long>) {
        transactionScope.transaction {
            // Full replacement: drop every existing row, then insert the new set. Delete + insert is
            // atomic inside the transaction, so a failed insert (e.g. an unknown tool id rejected by
            // the FK) leaves the previous tool set untouched.
            AgentRoleToolsTable.deleteWhere { AgentRoleToolsTable.roleId eq roleId }
            toolIds.forEach { toolId ->
                AgentRoleToolsTable.insert {
                    it[AgentRoleToolsTable.roleId] = roleId
                    it[AgentRoleToolsTable.toolDefinitionId] = toolId
                }
            }
        }
    }

    override suspend fun getRoleIdsUsingTool(toolId: Long): List<Long> =
        transactionScope.transaction {
            AgentRoleToolsTable
                .selectAll()
                .where { AgentRoleToolsTable.toolDefinitionId eq toolId }
                .map { it[AgentRoleToolsTable.roleId].value }
        }
}
