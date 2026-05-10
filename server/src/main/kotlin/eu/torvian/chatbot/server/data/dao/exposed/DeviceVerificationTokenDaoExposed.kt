package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.DeviceVerificationTokenDao
import eu.torvian.chatbot.server.data.entities.DeviceVerificationTokenEntity
import eu.torvian.chatbot.server.data.tables.DeviceVerificationTokensTable
import eu.torvian.chatbot.server.data.tables.mappers.toDeviceVerificationTokenEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of [DeviceVerificationTokenDao].
 */
class DeviceVerificationTokenDaoExposed(
    private val transactionScope: TransactionScope
) : DeviceVerificationTokenDao {

    override suspend fun createToken(
        userId: Long,
        deviceId: String,
        token: String,
        expiresAt: Long,
        createdAt: Long
    ): DeviceVerificationTokenEntity =
        transactionScope.transaction {
            val inserted = DeviceVerificationTokensTable.insert {
                it[DeviceVerificationTokensTable.userId] = userId
                it[DeviceVerificationTokensTable.deviceId] = deviceId
                it[DeviceVerificationTokensTable.token] = token
                it[DeviceVerificationTokensTable.expiresAt] = expiresAt
                it[DeviceVerificationTokensTable.createdAt] = createdAt
            }

            inserted.resultedValues?.firstOrNull()?.toDeviceVerificationTokenEntity()
                ?: throw IllegalStateException("Failed to read inserted verification token for user $userId, device $deviceId")
        }

    override suspend fun findToken(token: String): DeviceVerificationTokenEntity? =
        transactionScope.transaction {
            DeviceVerificationTokensTable.selectAll()
                .where { DeviceVerificationTokensTable.token eq token }
                .singleOrNull()
                ?.toDeviceVerificationTokenEntity()
        }

    override suspend fun deleteToken(token: String): Int =
        transactionScope.transaction {
            DeviceVerificationTokensTable.deleteWhere {
                DeviceVerificationTokensTable.token eq token
            }
        }

    override suspend fun getLastTokenCreatedAt(userId: Long, deviceId: String): Long? =
        transactionScope.transaction {
            DeviceVerificationTokensTable.selectAll()
                .where {
                    (DeviceVerificationTokensTable.userId eq userId) and
                        (DeviceVerificationTokensTable.deviceId eq deviceId)
                }
                .orderBy(DeviceVerificationTokensTable.createdAt to org.jetbrains.exposed.v1.core.SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(DeviceVerificationTokensTable.createdAt)
        }
}
