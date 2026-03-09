package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import eu.torvian.chatbot.server.data.dao.UserToolApprovalPreferenceDao
import eu.torvian.chatbot.server.data.dao.error.SetPreferenceError
import eu.torvian.chatbot.server.data.dao.error.UserToolApprovalPreferenceError
import eu.torvian.chatbot.server.data.tables.UserToolApprovalPreferencesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Exposed ORM implementation of [UserToolApprovalPreferenceDao].
 *
 * Provides database operations for user tool approval preferences using Exposed's SQL DSL.
 * All operations are wrapped in transactions managed by [TransactionScope].
 */
class UserToolApprovalPreferenceDaoExposed(
    private val transactionScope: TransactionScope
) : UserToolApprovalPreferenceDao {

    override suspend fun getPreference(
        userId: Long,
        toolDefinitionId: Long
    ): Either<UserToolApprovalPreferenceError.NotFound, UserToolApprovalPreference> =
        transactionScope.transaction {
            UserToolApprovalPreferencesTable
                .selectAll()
                .where {
                    (UserToolApprovalPreferencesTable.userId eq userId) and
                            (UserToolApprovalPreferencesTable.toolDefinitionId eq toolDefinitionId)
                }
                .singleOrNull()
                ?.toUserToolApprovalPreference()
                ?.right()
                ?: UserToolApprovalPreferenceError.NotFound(userId, toolDefinitionId).left()
        }

    override suspend fun getAllPreferencesForUser(userId: Long): List<UserToolApprovalPreference> =
        transactionScope.transaction {
            UserToolApprovalPreferencesTable
                .selectAll()
                .where { UserToolApprovalPreferencesTable.userId eq userId }
                .map { it.toUserToolApprovalPreference() }
        }

    override suspend fun setPreference(
        userId: Long,
        toolDefinitionId: Long,
        autoApprove: Boolean,
        conditions: String?,
        denialReason: String?
    ): Either<SetPreferenceError, UserToolApprovalPreference> =
        transactionScope.transaction {
            either {
                catch({
                    // Upsert the preference - let database enforce constraints
                    UserToolApprovalPreferencesTable.upsert {
                        it[UserToolApprovalPreferencesTable.userId] = userId
                        it[UserToolApprovalPreferencesTable.toolDefinitionId] = toolDefinitionId
                        it[UserToolApprovalPreferencesTable.autoApprove] = autoApprove
                        it[UserToolApprovalPreferencesTable.conditions] = conditions
                        it[UserToolApprovalPreferencesTable.denialReason] = denialReason
                    }

                    // Return the preference
                    UserToolApprovalPreference(
                        userId = userId,
                        toolDefinitionId = toolDefinitionId,
                        autoApprove = autoApprove,
                        conditions = conditions,
                        denialReason = denialReason
                    )
                }) { e: ExposedSQLException ->
                    val message =
                        "Failed to set tool approval preference for userId=$userId, toolDefinitionId=$toolDefinitionId"
                    ensure(!e.isForeignKeyViolation()) { SetPreferenceError.ForeignKeyViolation(message) }
                    throw e
                }
            }
        }

    override suspend fun deletePreference(
        userId: Long,
        toolDefinitionId: Long
    ): Either<UserToolApprovalPreferenceError.NotFound, Unit> =
        transactionScope.transaction {
            val deletedCount = UserToolApprovalPreferencesTable.deleteWhere {
                (UserToolApprovalPreferencesTable.userId eq userId) and
                        (UserToolApprovalPreferencesTable.toolDefinitionId eq toolDefinitionId)
            }

            if (deletedCount > 0) {
                Unit.right()
            } else {
                UserToolApprovalPreferenceError.NotFound(userId, toolDefinitionId).left()
            }
        }

    override suspend fun deleteAllPreferencesForUser(userId: Long): Int =
        transactionScope.transaction {
            UserToolApprovalPreferencesTable.deleteWhere {
                UserToolApprovalPreferencesTable.userId eq userId
            }
        }

    /**
     * Maps a database result row to a [UserToolApprovalPreference] model.
     */
    private fun ResultRow.toUserToolApprovalPreference() = UserToolApprovalPreference(
        userId = this[UserToolApprovalPreferencesTable.userId].value,
        toolDefinitionId = this[UserToolApprovalPreferencesTable.toolDefinitionId].value,
        autoApprove = this[UserToolApprovalPreferencesTable.autoApprove],
        conditions = this[UserToolApprovalPreferencesTable.conditions],
        denialReason = this[UserToolApprovalPreferencesTable.denialReason]
    )
}
