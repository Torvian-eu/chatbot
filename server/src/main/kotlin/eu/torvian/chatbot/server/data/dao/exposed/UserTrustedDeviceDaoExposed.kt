package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.UserTrustedDeviceDao
import eu.torvian.chatbot.server.data.entities.UserTrustedDeviceEntity
import eu.torvian.chatbot.server.data.tables.UserTrustedDevicesTable
import eu.torvian.chatbot.server.data.tables.mappers.toUserTrustedDeviceEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of [UserTrustedDeviceDao].
 *
 * Provides CRUD operations for the trusted devices whitelist only.
 * Does not handle acknowledgements - those are managed by SecurityAuditDao.
 */
class UserTrustedDeviceDaoExposed(
    private val transactionScope: TransactionScope
) : UserTrustedDeviceDao {

    override suspend fun getTrustedDevice(userId: Long, deviceId: String): UserTrustedDeviceEntity? =
        transactionScope.transaction {
            UserTrustedDevicesTable.selectAll()
                .where { (UserTrustedDevicesTable.userId eq userId) and (UserTrustedDevicesTable.deviceId eq deviceId) }
                .singleOrNull()
                ?.toUserTrustedDeviceEntity()
        }

    override suspend fun insertTrustedDevice(
        userId: Long,
        deviceId: String,
        ipAddress: String?,
        firstSeenAt: Long,
        lastUsedAt: Long
    ): UserTrustedDeviceEntity =
        transactionScope.transaction {
            val inserted = UserTrustedDevicesTable.insert {
                it[UserTrustedDevicesTable.userId] = userId
                it[UserTrustedDevicesTable.deviceId] = deviceId
                it[UserTrustedDevicesTable.lastIpAddress] = ipAddress
                it[UserTrustedDevicesTable.firstSeenAt] = firstSeenAt
                it[UserTrustedDevicesTable.lastUsedAt] = lastUsedAt
            }

            inserted.resultedValues?.firstOrNull()?.toUserTrustedDeviceEntity()
                ?: throw IllegalStateException("Failed to read inserted trusted device row for user $userId")
        }

    override suspend fun updateLastUsedAt(id: Long, lastUsedAt: Long, lastIpAddress: String?): Boolean =
        transactionScope.transaction {
            UserTrustedDevicesTable.update({ UserTrustedDevicesTable.id eq id }) {
                it[UserTrustedDevicesTable.lastUsedAt] = lastUsedAt
                it[UserTrustedDevicesTable.lastIpAddress] = lastIpAddress
            } > 0
        }

    override suspend fun getTrustedDevicesCount(userId: Long): Int =
        transactionScope.transaction {
            UserTrustedDevicesTable.selectAll()
                .where { UserTrustedDevicesTable.userId eq userId }
                .count()
                .toInt()
        }

    override suspend fun getTrustedDevices(userId: Long): List<UserTrustedDeviceEntity> =
        transactionScope.transaction {
            UserTrustedDevicesTable.selectAll()
                .where { UserTrustedDevicesTable.userId eq userId }
                .map { it.toUserTrustedDeviceEntity() }
        }

    override suspend fun deleteTrustedDevice(userId: Long, deviceId: String): Int =
        transactionScope.transaction {
            UserTrustedDevicesTable.deleteWhere {
                (UserTrustedDevicesTable.userId eq userId) and (UserTrustedDevicesTable.deviceId eq deviceId)
            }
        }
}
