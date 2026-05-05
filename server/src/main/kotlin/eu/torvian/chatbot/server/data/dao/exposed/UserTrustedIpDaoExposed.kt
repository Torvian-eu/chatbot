package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.UserTrustedIpDao
import eu.torvian.chatbot.server.data.entities.UserTrustedIpEntity
import eu.torvian.chatbot.server.data.tables.UserTrustedIpsTable
import eu.torvian.chatbot.server.data.tables.mappers.toUserTrustedIpEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.SortOrder

/**
 * Exposed implementation of [UserTrustedIpDao].
 */
class UserTrustedIpDaoExposed(
    private val transactionScope: TransactionScope
) : UserTrustedIpDao {
    override suspend fun getTrustedIp(userId: Long, ipAddress: String): UserTrustedIpEntity? =
        transactionScope.transaction {
            UserTrustedIpsTable.selectAll()
                .where { (UserTrustedIpsTable.userId eq userId) and (UserTrustedIpsTable.ipAddress eq ipAddress) }
                .singleOrNull()
                ?.toUserTrustedIpEntity()
        }

    override suspend fun insertTrustedIp(
        userId: Long,
        ipAddress: String,
        isTrusted: Boolean,
        isAcknowledged: Boolean,
        firstUsedAt: Long,
        lastUsedAt: Long
    ): UserTrustedIpEntity =
        transactionScope.transaction {
            val inserted = UserTrustedIpsTable.insert {
                it[UserTrustedIpsTable.userId] = userId
                it[UserTrustedIpsTable.ipAddress] = ipAddress
                it[UserTrustedIpsTable.isTrusted] = isTrusted
                it[UserTrustedIpsTable.isAcknowledged] = isAcknowledged
                it[UserTrustedIpsTable.firstUsedAt] = firstUsedAt
                it[UserTrustedIpsTable.lastUsedAt] = lastUsedAt
            }

            inserted.resultedValues?.firstOrNull()?.toUserTrustedIpEntity()
                ?: throw IllegalStateException("Failed to read inserted trusted IP row for user $userId")
        }

    override suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long): Boolean =
        transactionScope.transaction {
            UserTrustedIpsTable.update({ UserTrustedIpsTable.id eq id }) {
                it[UserTrustedIpsTable.lastUsedAt] = lastUsedAt
            } > 0
        }

    override suspend fun acknowledgeTrustedIps(userId: Long): Int =
        transactionScope.transaction {
            UserTrustedIpsTable.update({
                (UserTrustedIpsTable.userId eq userId) and (UserTrustedIpsTable.isAcknowledged eq false)
            }) {
                it[UserTrustedIpsTable.isAcknowledged] = true
            }
        }

    override suspend fun getUnacknowledgedByUserId(userId: Long): List<UserTrustedIpEntity> =
        transactionScope.transaction {
            UserTrustedIpsTable.selectAll()
                .where {
                    (UserTrustedIpsTable.userId eq userId) and (UserTrustedIpsTable.isAcknowledged eq false)
                }
                .orderBy(UserTrustedIpsTable.lastUsedAt to SortOrder.DESC)
                .map { it.toUserTrustedIpEntity() }
        }
}
