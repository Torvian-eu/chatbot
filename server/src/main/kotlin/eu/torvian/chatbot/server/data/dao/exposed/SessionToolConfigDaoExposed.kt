package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.SessionToolConfigDao
import eu.torvian.chatbot.server.data.dao.error.ClearToolConfigError
import eu.torvian.chatbot.server.data.dao.error.SetToolEnabledError
import eu.torvian.chatbot.server.data.tables.SessionToolConfigTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.mappers.toToolDefinition
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Exposed ORM implementation of [SessionToolConfigDao].
 *
 * Manages session-specific tool configurations using Exposed's SQL DSL.
 * All operations are wrapped in transactions managed by [TransactionScope].
 */
class SessionToolConfigDaoExposed(
    private val transactionScope: TransactionScope
) : SessionToolConfigDao {

    override suspend fun getEnabledToolsForSession(sessionId: Long): List<ToolDefinition> =
        transactionScope.transaction {
            SessionToolConfigTable
                .innerJoin(
                    ToolDefinitionTable,
                    { toolDefinitionId },
                    { ToolDefinitionTable.id }
                )
                .selectAll().where {
                    (SessionToolConfigTable.sessionId eq sessionId) and
                            (SessionToolConfigTable.isEnabled eq true) and
                            (ToolDefinitionTable.isEnabled eq true)
                }
                .map { it.toToolDefinition() }
        }

    override suspend fun isToolEnabledForSession(sessionId: Long, toolDefinitionId: Long): Boolean =
        transactionScope.transaction {
            SessionToolConfigTable
                .selectAll().where {
                    (SessionToolConfigTable.sessionId eq sessionId) and
                            (SessionToolConfigTable.toolDefinitionId eq toolDefinitionId) and
                            (SessionToolConfigTable.isEnabled eq true)
                }
                .count() > 0
        }

    override suspend fun setToolEnabledForSession(
        sessionId: Long,
        toolDefinitionId: Long,
        enabled: Boolean
    ): Either<SetToolEnabledError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    SessionToolConfigTable.upsert {
                        it[SessionToolConfigTable.sessionId] = sessionId
                        it[SessionToolConfigTable.toolDefinitionId] = toolDefinitionId
                        it[isEnabled] = enabled
                    }
                }) { e: ExposedSQLException ->
                    ensure(!e.isForeignKeyViolation()) {
                        SetToolEnabledError.ForeignKeyViolation(
                            "Foreign key constraint violation: sessionId=$sessionId, toolDefinitionId=$toolDefinitionId"
                        )
                    }
                    throw e
                }
            }
        }

    override suspend fun getSessionsUsingTool(toolDefinitionId: Long): List<Long> =
        transactionScope.transaction {
            SessionToolConfigTable
                .selectAll().where {
                    (SessionToolConfigTable.toolDefinitionId eq toolDefinitionId) and
                            (SessionToolConfigTable.isEnabled eq true)
                }
                .map { it[SessionToolConfigTable.sessionId].value }
        }

    override suspend fun clearSessionToolConfig(sessionId: Long): Either<ClearToolConfigError, Unit> =
        transactionScope.transaction {
            SessionToolConfigTable.deleteWhere {
                SessionToolConfigTable.sessionId eq sessionId
            }
            // Always succeeds, even if no rows deleted
            Unit.right()
        }
}

