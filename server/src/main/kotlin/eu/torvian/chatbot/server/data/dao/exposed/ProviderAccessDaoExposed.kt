package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.server.data.dao.ProviderAccessDao
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError
import eu.torvian.chatbot.server.data.entities.UserGroupEntity
import eu.torvian.chatbot.server.data.tables.LLMProviderAccessTable
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
 * Exposed implementation of the [ProviderAccessDao].
 */
class ProviderAccessDaoExposed(
    private val transactionScope: TransactionScope
) : ProviderAccessDao {

    override suspend fun getAccessGroups(providerId: Long, accessMode: String): List<UserGroupEntity> =
        transactionScope.transaction {
            (LLMProviderAccessTable innerJoin UserGroupsTable)
                .selectAll()
                .where {
                    (LLMProviderAccessTable.providerId eq providerId) and
                            (LLMProviderAccessTable.accessMode eq accessMode)
                }
                .map { it.toUserGroupEntity() }
        }

    override suspend fun getAccessGroups(providerId: Long): Map<String, List<UserGroupEntity>> =
        transactionScope.transaction {
            (LLMProviderAccessTable innerJoin UserGroupsTable)
                .selectAll()
                .where { LLMProviderAccessTable.providerId eq providerId }
                .groupBy { it[LLMProviderAccessTable.accessMode] }
                .mapValues { (_, rows) -> rows.map { it.toUserGroupEntity() } }
        }

    override suspend fun hasAccess(
        providerId: Long,
        groupIds: List<Long>,
        accessMode: String
    ): Boolean =
        transactionScope.transaction {
            if (groupIds.isEmpty()) return@transaction false

            LLMProviderAccessTable
                .selectAll()
                .where {
                    (LLMProviderAccessTable.providerId eq providerId) and
                            (LLMProviderAccessTable.userGroupId inList groupIds) and
                            (LLMProviderAccessTable.accessMode eq accessMode)
                }
                .count() > 0
        }

    override suspend fun grantAccess(
        providerId: Long,
        groupId: Long,
        accessMode: String
    ): Either<GrantAccessError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    LLMProviderAccessTable.insert {
                        it[LLMProviderAccessTable.providerId] = providerId
                        it[userGroupId] = groupId
                        it[LLMProviderAccessTable.accessMode] = accessMode
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(GrantAccessError.ForeignKeyViolation(providerId.toString(), groupId))

                        e.isUniqueConstraintViolation() ->
                            raise(GrantAccessError.AlreadyGranted)

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun revokeAccess(
        providerId: Long,
        groupId: Long,
        accessMode: String
    ): Either<RevokeAccessError, Unit> =
        transactionScope.transaction {
            either {
                val deleted = LLMProviderAccessTable.deleteWhere {
                    (LLMProviderAccessTable.providerId eq providerId) and
                            (LLMProviderAccessTable.userGroupId eq groupId) and
                            (LLMProviderAccessTable.accessMode eq accessMode)
                }
                ensure(deleted > 0) {
                    RevokeAccessError.AccessNotGranted
                }
            }
        }

    override suspend fun revokeAllAccess(providerId: Long, groupId: Long): Either<RevokeAccessError, Unit> =
        transactionScope.transaction {
            either {
                val deleted = LLMProviderAccessTable.deleteWhere {
                    (LLMProviderAccessTable.providerId eq providerId) and
                            (LLMProviderAccessTable.userGroupId eq groupId)
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

            LLMProviderAccessTable
                .select(LLMProviderAccessTable.providerId)
                .where {
                    (LLMProviderAccessTable.userGroupId inList groupIds) and
                            (LLMProviderAccessTable.accessMode eq accessMode)
                }
                .withDistinct()
                .map { it[LLMProviderAccessTable.providerId].value }
        }
}
