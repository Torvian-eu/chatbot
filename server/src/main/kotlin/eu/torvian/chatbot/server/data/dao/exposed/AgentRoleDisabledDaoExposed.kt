package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.AgentRoleDisabledDao
import eu.torvian.chatbot.server.data.tables.AgentRoleDisabledTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of [AgentRoleDisabledDao] over the `agent_role_disabled` table.
 *
 * Reads and writes are always scoped by the requesting user id. The batch read filters on the
 * composite PK's `role_id` prefix and then applies the `user_id` equality, so a user-scoped lookup
 * needs no extra index; writes use delete-then-insert so both `true` and `false` are idempotent
 * (mirrors `AgentRoleSpawnableRoleDaoExposed.replaceSpawnableRolesForRole`).
 *
 * @property transactionScope Transaction wrapper for database access.
 */
class AgentRoleDisabledDaoExposed(
    private val transactionScope: TransactionScope
) : AgentRoleDisabledDao {

    override suspend fun getDisabledRoleIds(userId: Long, roleIds: List<Long>): Set<Long> =
        transactionScope.transaction {
            // Empty-guard mirrors the spawnable DAO: an `IN ()` clause would be invalid SQL.
            if (roleIds.isEmpty()) return@transaction emptySet()
            AgentRoleDisabledTable
                .selectAll()
                .where {
                    (AgentRoleDisabledTable.roleId inList roleIds) and
                        (AgentRoleDisabledTable.userId eq userId)
                }
                .map { it[AgentRoleDisabledTable.roleId].value }
                .toSet()
        }

    override suspend fun isRoleDisabled(userId: Long, roleId: Long): Boolean =
        transactionScope.transaction {
            // Same indexed PK-prefix lookup shape as the batch read, collapsed to an existence check.
            AgentRoleDisabledTable
                .selectAll()
                .where {
                    (AgentRoleDisabledTable.roleId eq roleId) and
                        (AgentRoleDisabledTable.userId eq userId)
                }
                .count() > 0
        }

    override suspend fun setRoleDisabled(userId: Long, roleId: Long, disabled: Boolean) {
        transactionScope.transaction {
            if (disabled) {
                // Delete-then-insert keeps the write idempotent (the composite PK would reject a
                // duplicate row) and consistent with the spawn allow-list DAO's replace pattern.
                AgentRoleDisabledTable.deleteWhere {
                    (AgentRoleDisabledTable.roleId eq roleId) and
                        (AgentRoleDisabledTable.userId eq userId)
                }
                AgentRoleDisabledTable.insert {
                    it[AgentRoleDisabledTable.roleId] = roleId
                    it[AgentRoleDisabledTable.userId] = userId
                }
            } else {
                AgentRoleDisabledTable.deleteWhere {
                    (AgentRoleDisabledTable.roleId eq roleId) and
                        (AgentRoleDisabledTable.userId eq userId)
                }
            }
        }
    }
}