package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.server.data.dao.ModelAccessDao
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError
import eu.torvian.chatbot.server.data.entities.UserGroupEntity
import eu.torvian.chatbot.server.data.tables.LLMModelAccessTable
import eu.torvian.chatbot.server.data.tables.UserGroupsTable
import eu.torvian.chatbot.server.data.tables.mappers.toUserGroupEntity
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/**
 * Exposed implementation of the [ModelAccessDao].
 */
class ModelAccessDaoExposed(
    private val transactionScope: TransactionScope
) : ModelAccessDao {

    override suspend fun getAccessGroups(modelId: Long, accessMode: String): List<UserGroupEntity> =
        transactionScope.transaction {
            (LLMModelAccessTable innerJoin UserGroupsTable)
                .selectAll()
                .where {
                    (LLMModelAccessTable.modelId eq modelId) and
                            (LLMModelAccessTable.accessMode eq accessMode)
                }
                .map { it.toUserGroupEntity() }
        }

    override suspend fun getAccessGroups(modelId: Long): Map<String, List<UserGroupEntity>> =
        transactionScope.transaction {
            (LLMModelAccessTable innerJoin UserGroupsTable)
                .selectAll()
                .where { LLMModelAccessTable.modelId eq modelId }
                .groupBy { it[LLMModelAccessTable.accessMode] }
                .mapValues { (_, rows) -> rows.map { it.toUserGroupEntity() } }
        }

    override suspend fun hasAccess(
        modelId: Long,
        groupIds: List<Long>,
        accessMode: String
    ): Boolean =
        transactionScope.transaction {
            if (groupIds.isEmpty()) return@transaction false

            LLMModelAccessTable
                .selectAll()
                .where {
                    (LLMModelAccessTable.modelId eq modelId) and
                            (LLMModelAccessTable.userGroupId inList groupIds) and
                            (LLMModelAccessTable.accessMode eq accessMode)
                }
                .count() > 0
        }

    override suspend fun grantAccess(
        modelId: Long,
        groupId: Long,
        accessMode: String
    ): Either<GrantAccessError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    LLMModelAccessTable.insert {
                        it[LLMModelAccessTable.modelId] = modelId
                        it[userGroupId] = groupId
                        it[LLMModelAccessTable.accessMode] = accessMode
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(GrantAccessError.ForeignKeyViolation(modelId.toString(), groupId))

                        e.isUniqueConstraintViolation() ->
                            raise(GrantAccessError.AlreadyGranted)

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun revokeAccess(
        modelId: Long,
        groupId: Long,
        accessMode: String
    ): Either<RevokeAccessError, Unit> =
        transactionScope.transaction {
            either {
                val deleted = LLMModelAccessTable.deleteWhere {
                    (LLMModelAccessTable.modelId eq modelId) and
                            (LLMModelAccessTable.userGroupId eq groupId) and
                            (LLMModelAccessTable.accessMode eq accessMode)
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

            LLMModelAccessTable
                .select(LLMModelAccessTable.modelId)
                .where {
                    (LLMModelAccessTable.userGroupId inList groupIds) and
                            (LLMModelAccessTable.accessMode eq accessMode)
                }
                .withDistinct()
                .map { it[LLMModelAccessTable.modelId].value }
        }
}
