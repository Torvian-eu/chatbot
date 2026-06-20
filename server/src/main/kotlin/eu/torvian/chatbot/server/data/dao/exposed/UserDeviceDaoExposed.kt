package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.entities.UserDeviceEntity
import eu.torvian.chatbot.server.data.tables.UserDevicesTable
import eu.torvian.chatbot.server.data.tables.mappers.toUserDeviceEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Exposed implementation of [UserDeviceDao].
 *
 * The device registry is used to resolve client-side UUIDs into stable internal identifiers
 * for device-scoped preferences and login telemetry.
 */
class UserDeviceDaoExposed(
    private val transactionScope: TransactionScope
) : UserDeviceDao {
    override suspend fun getDeviceById(id: Long): UserDeviceEntity? =
        transactionScope.transaction {
            UserDevicesTable.selectAll()
                .where { UserDevicesTable.id eq id }
                .singleOrNull()
                ?.toUserDeviceEntity()
        }

    override suspend fun getDeviceByClientId(userId: Long, clientDeviceId: String): UserDeviceEntity? =
        transactionScope.transaction {
            UserDevicesTable.selectAll()
                .where {
                    (UserDevicesTable.userId eq userId) and (UserDevicesTable.clientDeviceId eq clientDeviceId)
                }
                .singleOrNull()
                ?.toUserDeviceEntity()
        }

    override suspend fun insertDevice(userId: Long, clientDeviceId: String, name: String?): UserDeviceEntity =
        transactionScope.transaction {
            val now = System.currentTimeMillis()
            val inserted = UserDevicesTable.insert {
                it[UserDevicesTable.userId] = userId
                it[UserDevicesTable.clientDeviceId] = clientDeviceId
                it[UserDevicesTable.deviceName] = name
                it[UserDevicesTable.createdAt] = now
                it[UserDevicesTable.lastUsedAt] = now
            }

            inserted.resultedValues?.firstOrNull()?.toUserDeviceEntity()
                ?: getDeviceByClientId(userId, clientDeviceId)
                ?: throw IllegalStateException("Failed to read inserted device row for user $userId and device $clientDeviceId")
        }

    override suspend fun updateDeviceUsage(id: Long, lastUsedAt: Long): Boolean =
        transactionScope.transaction {
            UserDevicesTable.update({ UserDevicesTable.id eq id }) {
                it[UserDevicesTable.lastUsedAt] = lastUsedAt
            } > 0
        }
}


