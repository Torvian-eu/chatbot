package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.ProjectAgentRoleDao
import eu.torvian.chatbot.server.data.tables.AgentRoleTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of the [ProjectAgentRoleDao].
 *
 * Operates on the single nullable `agent_roles.project_id` membership column (the role side of the
 * relation; a role belongs to at most one project). Reads return member role ids as sets (the
 * project side is deliberately unordered); writes are full replacements from the project side
 * (detach removed roles, attach new ones) because the project update flow rewrites the project's
 * whole role set.
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class ProjectAgentRoleDaoExposed(
    private val transactionScope: TransactionScope
) : ProjectAgentRoleDao {

    override suspend fun getRoleIdsForProject(projectId: Long): Set<Long> =
        transactionScope.transaction {
            AgentRoleTable
                .selectAll()
                .where { AgentRoleTable.projectId eq projectId }
                .map { it[AgentRoleTable.id].value }
                .toSet()
        }

    override suspend fun getRoleIdsForProjects(projectIds: List<Long>): Map<Long, Set<Long>> =
        transactionScope.transaction {
            if (projectIds.isEmpty()) {
                return@transaction emptyMap()
            }
            AgentRoleTable
                .selectAll()
                .where { AgentRoleTable.projectId inList projectIds }
                // The `inList` predicate already excludes nulls, so the group key is non-null.
                .groupBy { it[AgentRoleTable.projectId]!!.value }
                .mapValues { (_, rows) -> rows.map { it[AgentRoleTable.id].value }.toSet() }
        }

    override suspend fun replaceRolesForProject(projectId: Long, roleIds: Set<Long>) {
        transactionScope.transaction {
            // Detach first, then attach, atomically inside the transaction. The order keeps the
            // membership consistent at every intermediate step: removed roles are unassociated before
            // the new ones are attached, and a failed attach leaves the detach visible but never
            // half-merged (SQLite single-writer transactions roll back on error anyway).
            if (roleIds.isEmpty()) {
                AgentRoleTable.update({ AgentRoleTable.projectId eq projectId }) {
                    it[AgentRoleTable.projectId] = null
                }
            } else {
                AgentRoleTable.update({
                    (AgentRoleTable.projectId eq projectId) and (AgentRoleTable.id notInList roleIds)
                }) {
                    it[AgentRoleTable.projectId] = null
                }
                // The caller has validated that none of these roles belongs to another project, so a
                // plain reassignment can only attach unassociated roles or re-affirm existing members.
                AgentRoleTable.update({ AgentRoleTable.id inList roleIds }) {
                    it[AgentRoleTable.projectId] = projectId
                }
            }
        }
    }
}