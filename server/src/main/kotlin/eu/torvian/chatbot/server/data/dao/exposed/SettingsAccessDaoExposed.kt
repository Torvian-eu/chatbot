package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.server.data.dao.SettingsAccessDao
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError
import eu.torvian.chatbot.server.data.entities.UserGroupEntity
import eu.torvian.chatbot.server.data.tables.ModelSettingsAccessTable
import eu.torvian.chatbot.server.data.tables.UserGroupsTable
import eu.torvian.chatbot.server.data.tables.mappers.toUserGroupEntity
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/**
 * Exposed implementation of the [SettingsAccessDao].
 */
class SettingsAccessDaoExposed(
    private val transactionScope: TransactionScope
) : SettingsAccessDao {

    override suspend fun getAccessGroups(settingsId: Long, accessMode: String): List<UserGroupEntity> =
        transactionScope.transaction {
            (ModelSettingsAccessTable innerJoin UserGroupsTable)
                .selectAll()
                .where {
                    (ModelSettingsAccessTable.settingsId eq settingsId) and
                            (ModelSettingsAccessTable.accessMode eq accessMode)
                }
                .map { it.toUserGroupEntity() }
        }

    override suspend fun getAccessGroups(settingsId: Long): Map<String, List<UserGroupEntity>> =
        transactionScope.transaction {
            // select rows for the settings id and join with groups
            (ModelSettingsAccessTable innerJoin UserGroupsTable)
                .selectAll()
                .where { ModelSettingsAccessTable.settingsId eq settingsId }
                .groupBy { it[ModelSettingsAccessTable.accessMode] }
                .mapValues { entry -> entry.value.map { it.toUserGroupEntity() } }
        }

    override suspend fun hasAccess(
        settingsId: Long,
        groupIds: List<Long>,
        accessMode: String
    ): Boolean =
        transactionScope.transaction {
            if (groupIds.isEmpty()) return@transaction false

            ModelSettingsAccessTable
                .selectAll()
                .where {
                    (ModelSettingsAccessTable.settingsId eq settingsId) and
                            (ModelSettingsAccessTable.userGroupId inList groupIds) and
                            (ModelSettingsAccessTable.accessMode eq accessMode)
                }
                .count() > 0
        }

    override suspend fun grantAccess(
        settingsId: Long,
        groupId: Long,
        accessMode: String
    ): Either<GrantAccessError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    ModelSettingsAccessTable.insert {
                        it[ModelSettingsAccessTable.settingsId] = settingsId
                        it[userGroupId] = groupId
                        it[ModelSettingsAccessTable.accessMode] = accessMode
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(GrantAccessError.ForeignKeyViolation(settingsId.toString(), groupId))

                        e.isUniqueConstraintViolation() ->
                            raise(GrantAccessError.AlreadyGranted)

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun revokeAccess(
        settingsId: Long,
        groupId: Long,
        accessMode: String
    ): Either<RevokeAccessError, Unit> =
        transactionScope.transaction {
            either {
                val deleted = ModelSettingsAccessTable.deleteWhere {
                    (ModelSettingsAccessTable.settingsId eq settingsId) and
                            (ModelSettingsAccessTable.userGroupId eq groupId) and
                            (ModelSettingsAccessTable.accessMode eq accessMode)
                }
                ensure(deleted > 0) {
                    RevokeAccessError.AccessNotGranted
                }
            }
        }

    override suspend fun revokeAllAccess(settingsId: Long, groupId: Long): Either<RevokeAccessError, Unit> =
        transactionScope.transaction {
            either {
                val deleted = ModelSettingsAccessTable.deleteWhere {
                    (ModelSettingsAccessTable.settingsId eq settingsId) and
                            (ModelSettingsAccessTable.userGroupId eq groupId)
                }
                ensure(deleted > 0) {
                    RevokeAccessError.AccessNotGranted
                }
            }
        }

    override suspend fun getResourcesAccessibleByGroups(
        groupIds: List<Long>,
        accessMode: String
    ): List<Long> =
        transactionScope.transaction {
            if (groupIds.isEmpty()) return@transaction emptyList()

            ModelSettingsAccessTable
                .select(ModelSettingsAccessTable.settingsId)
                .where {
                    (ModelSettingsAccessTable.userGroupId inList groupIds) and
                            (ModelSettingsAccessTable.accessMode eq accessMode)
                }
                .withDistinct()
                .map { it[ModelSettingsAccessTable.settingsId].value }
        }
}
