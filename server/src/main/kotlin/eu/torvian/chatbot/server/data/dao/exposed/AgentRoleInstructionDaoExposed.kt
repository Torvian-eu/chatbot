package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao.InstructionRef
import eu.torvian.chatbot.server.data.tables.AgentRoleInstructionTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [AgentRoleInstructionDao].
 *
 * Operates on the `agent_role_instructions` link table. The composite primary key rejects duplicate
 * links at the storage level (a backstop for the typed service checks), and the `sequence` column
 * keeps each role's list ordered.
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class AgentRoleInstructionDaoExposed(
    private val transactionScope: TransactionScope
) : AgentRoleInstructionDao {

    override suspend fun getLinksForRoles(roleIds: List<Long>): Map<Long, List<InstructionRef>> =
        transactionScope.transaction {
            if (roleIds.isEmpty()) return@transaction emptyMap()
            AgentRoleInstructionTable
                .selectAll()
                .where { AgentRoleInstructionTable.agentRoleId inList roleIds }
                .groupBy { it[AgentRoleInstructionTable.agentRoleId].value }
                .mapValues { (_, rows) ->
                    // Callers rebuild ordered lists directly from the refs, so sort here and keep
                    // every read path order-stable regardless of query order.
                    rows.map {
                        InstructionRef(
                            instructionId = it[AgentRoleInstructionTable.instructionId].value,
                            sequence = it[AgentRoleInstructionTable.sequence]
                        )
                    }.sortedBy { it.sequence }
                }
        }

    override suspend fun getLinkedRoleIdsForInstructions(instructionIds: List<Long>): Map<Long, Set<Long>> =
        transactionScope.transaction {
            if (instructionIds.isEmpty()) return@transaction emptyMap()
            AgentRoleInstructionTable
                .selectAll()
                .where { AgentRoleInstructionTable.instructionId inList instructionIds }
                .groupBy { it[AgentRoleInstructionTable.instructionId].value }
                .mapValues { (_, rows) ->
                    // The ids are a set (a row links a role at most once); the ascending query order is
                    // kept as the iteration order so every wire payload stays deterministic.
                    rows.map { it[AgentRoleInstructionTable.agentRoleId].value }.sorted().toSet()
                }
        }

    override suspend fun replaceInstructionsForRole(roleId: Long, orderedInstructionIds: List<Long>) {
        transactionScope.transaction {
            AgentRoleInstructionTable.deleteWhere { AgentRoleInstructionTable.agentRoleId eq roleId }
            orderedInstructionIds.forEachIndexed { sequence, instructionId ->
                AgentRoleInstructionTable.insert {
                    it[AgentRoleInstructionTable.agentRoleId] = roleId
                    it[AgentRoleInstructionTable.instructionId] = instructionId
                    it[AgentRoleInstructionTable.sequence] = sequence
                }
            }
        }
    }

    override suspend fun appendInstructionForRole(roleId: Long, instructionId: Long) {
        transactionScope.transaction {
            // Append-last semantics: the new link goes behind the role's current maximum position
            // (an empty role starts at zero).
            val nextSequence = AgentRoleInstructionTable
                .selectAll()
                .where { AgentRoleInstructionTable.agentRoleId eq roleId }
                .map { it[AgentRoleInstructionTable.sequence] }
                .maxOrNull()
                ?.plus(1)
                ?: 0
            AgentRoleInstructionTable.insert {
                it[AgentRoleInstructionTable.agentRoleId] = roleId
                it[AgentRoleInstructionTable.instructionId] = instructionId
                it[AgentRoleInstructionTable.sequence] = nextSequence
            }
        }
    }

    override suspend fun removeInstructionFromRole(roleId: Long, instructionId: Long) {
        transactionScope.transaction {
            AgentRoleInstructionTable.deleteWhere {
                (AgentRoleInstructionTable.agentRoleId eq roleId) and
                    (AgentRoleInstructionTable.instructionId eq instructionId)
            }
        }
    }
}
