package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.entities.UserPreferenceEntity
import eu.torvian.chatbot.server.data.tables.UserPreferencesTable
import eu.torvian.chatbot.server.data.tables.mappers.toUserPreferenceEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Exposed implementation of [UserPreferenceDao].
 *
 * The DAO uses scopeId ("GLOBAL" or clientDeviceId) for unique constraint and upsert operations,
 * while maintaining deviceId as a nullable FK for relational integrity.
 */
class UserPreferenceDaoExposed(
    private val transactionScope: TransactionScope
) : UserPreferenceDao {
    override suspend fun getPreferencesForUser(userId: Long, internalDeviceId: Long?): List<UserPreferenceEntity> =
        transactionScope.transaction {
            UserPreferencesTable.selectAll()
                .where {
                    val deviceScope = when (internalDeviceId) {
                        null -> UserPreferencesTable.deviceId.isNull()
                        else -> UserPreferencesTable.deviceId.isNull() or (UserPreferencesTable.deviceId eq internalDeviceId)
                    }

                    (UserPreferencesTable.userId eq userId) and deviceScope
                }
                .map { it.toUserPreferenceEntity() }
        }

    override suspend fun upsertPreference(
        userId: Long,
        internalDeviceId: Long?,
        clientDeviceId: String?,
        key: String,
        value: String
    ) {
        transactionScope.transaction {
            val now = System.currentTimeMillis()
            // Use "GLOBAL" for null clientDeviceId, otherwise use the clientDeviceId as scopeId
            val scopeId = clientDeviceId ?: "GLOBAL"
            UserPreferencesTable.upsert(
                keys = arrayOf(UserPreferencesTable.userId, UserPreferencesTable.scopeId, UserPreferencesTable.prefKey),
                where = {
                    (UserPreferencesTable.userId eq userId) and (UserPreferencesTable.prefKey eq key) and
                            (UserPreferencesTable.scopeId eq scopeId)
                }) {
                it[UserPreferencesTable.userId] = userId
                it[UserPreferencesTable.deviceId] = internalDeviceId
                it[UserPreferencesTable.scopeId] = scopeId
                it[UserPreferencesTable.prefKey] = key
                it[UserPreferencesTable.prefValue] = value
                it[UserPreferencesTable.updatedAt] = now
            }
        }
    }

    override suspend fun deletePreference(userId: Long, internalDeviceId: Long?, key: String) {
        transactionScope.transaction {
            UserPreferencesTable.deleteWhere {
                val deviceScope = when (internalDeviceId) {
                    null -> UserPreferencesTable.deviceId.isNull()
                    else -> UserPreferencesTable.deviceId eq internalDeviceId
                }

                (UserPreferencesTable.userId eq userId) and deviceScope and (UserPreferencesTable.prefKey eq key)
            }
        }
    }
}
