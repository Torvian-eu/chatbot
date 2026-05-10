package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.security.SecurityAuditStatus
import eu.torvian.chatbot.server.data.dao.SecurityAuditDao
import eu.torvian.chatbot.server.data.entities.SecurityAuditEntity
import eu.torvian.chatbot.server.data.tables.SecurityAuditTable
import eu.torvian.chatbot.server.data.tables.mappers.toSecurityAuditEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of [SecurityAuditDao].
 */
class SecurityAuditDaoExposed(
    private val transactionScope: TransactionScope
) : SecurityAuditDao {

    override suspend fun insertAuditRecord(
        userId: Long,
        deviceId: String,
        ipAddress: String?,
        createdAt: Long
    ): SecurityAuditEntity =
        transactionScope.transaction {
            val inserted = SecurityAuditTable.insert {
                it[SecurityAuditTable.userId] = userId
                it[SecurityAuditTable.deviceId] = deviceId
                it[SecurityAuditTable.ipAddress] = ipAddress
                it[SecurityAuditTable.createdAt] = createdAt
                it[SecurityAuditTable.status] = SecurityAuditStatus.PENDING.name
            }

            inserted.resultedValues?.firstOrNull()?.toSecurityAuditEntity()
                ?: throw IllegalStateException("Failed to read inserted security audit row for user $userId")
        }

    override suspend fun getUnacknowledgedByUserId(userId: Long): List<SecurityAuditEntity> =
        transactionScope.transaction {
            SecurityAuditTable.selectAll()
                .where {
                    (SecurityAuditTable.userId eq userId) and (SecurityAuditTable.status eq SecurityAuditStatus.PENDING.name)
                }
                .orderBy(SecurityAuditTable.createdAt to SortOrder.DESC)
                .map { it.toSecurityAuditEntity() }
        }

    override suspend fun updateStatus(id: Long, status: SecurityAuditStatus, resolvedAt: Long): Int =
        transactionScope.transaction {
            SecurityAuditTable.update({ SecurityAuditTable.id eq id }) {
                it[SecurityAuditTable.status] = status.name
                it[SecurityAuditTable.resolvedAt] = resolvedAt
            }
        }

    override suspend fun getAuditRecordById(id: Long): SecurityAuditEntity? =
        transactionScope.transaction {
            SecurityAuditTable.selectAll()
                .where { SecurityAuditTable.id eq id }
                .singleOrNull()
                ?.toSecurityAuditEntity()
        }
}
