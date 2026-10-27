package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.WorkerCredentialProfileAgentRoleDao
import eu.torvian.chatbot.server.data.tables.WorkerCredentialProfileAgentRolesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of [WorkerCredentialProfileAgentRoleDao].
 *
 * Reads return role ids ascending so the relayed DTO is deterministic regardless of row order.
 * Writes are full replacements (delete + insert) because a profile edit already rewrites the whole
 * signed body, and therefore its whole link set.
 *
 * @property transactionScope Transaction boundary used for every statement.
 */
class WorkerCredentialProfileAgentRoleDaoExposed(
    private val transactionScope: TransactionScope,
) : WorkerCredentialProfileAgentRoleDao {

    override suspend fun getAgentRoleIds(profileId: Long): Set<Long> =
        transactionScope.transaction {
            WorkerCredentialProfileAgentRolesTable
                .selectAll()
                .where { WorkerCredentialProfileAgentRolesTable.profileId eq profileId }
                .map { it[WorkerCredentialProfileAgentRolesTable.agentRoleId].value }
                .sorted()
                .toSet()
        }

    override suspend fun getAgentRoleIdsByProfileIds(profileIds: List<Long>): Map<Long, Set<Long>> =
        transactionScope.transaction {
            if (profileIds.isEmpty()) {
                return@transaction emptyMap()
            }
            WorkerCredentialProfileAgentRolesTable
                .selectAll()
                .where { WorkerCredentialProfileAgentRolesTable.profileId inList profileIds }
                .groupBy { it[WorkerCredentialProfileAgentRolesTable.profileId].value }
                .mapValues { (_, rows) ->
                    rows.map { it[WorkerCredentialProfileAgentRolesTable.agentRoleId].value }
                        .sorted()
                        .toSet()
                }
        }

    override suspend fun getProfileIdsByAgentRoleId(agentRoleId: Long): Set<Long> =
        transactionScope.transaction {
            WorkerCredentialProfileAgentRolesTable
                .selectAll()
                .where { WorkerCredentialProfileAgentRolesTable.agentRoleId eq agentRoleId }
                .map { it[WorkerCredentialProfileAgentRolesTable.profileId].value }
                .toSet()
        }

    override suspend fun replaceAgentRoleIds(profileId: Long, agentRoleIds: Set<Long>) {
        transactionScope.transaction {
            // Full replacement: drop every existing row, then insert the new set. Both statements run
            // in one transaction, so a failed insert leaves the previous link set untouched.
            WorkerCredentialProfileAgentRolesTable.deleteWhere {
                WorkerCredentialProfileAgentRolesTable.profileId eq profileId
            }
            agentRoleIds.forEach { agentRoleId ->
                WorkerCredentialProfileAgentRolesTable.insert {
                    it[WorkerCredentialProfileAgentRolesTable.profileId] = profileId
                    it[WorkerCredentialProfileAgentRolesTable.agentRoleId] = agentRoleId
                }
            }
        }
    }
}
