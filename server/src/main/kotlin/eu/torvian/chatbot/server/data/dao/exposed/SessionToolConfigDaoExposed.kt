package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.SessionToolConfigDao
import eu.torvian.chatbot.server.data.dao.error.ClearToolConfigError
import eu.torvian.chatbot.server.data.dao.error.SetToolEnabledError
import eu.torvian.chatbot.server.data.dao.error.SetToolsEnabledError
import eu.torvian.chatbot.server.data.tables.LocalMCPServerTable
import eu.torvian.chatbot.server.data.tables.LocalMCPToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.SessionToolConfigTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.mappers.toToolDefinition
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

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
                ).leftJoin(
                    LocalMCPToolDefinitionTable,
                    { ToolDefinitionTable.id },
                    { LocalMCPToolDefinitionTable.toolDefinitionId }
                ).leftJoin(
                    LocalMCPServerTable,
                    { LocalMCPToolDefinitionTable.mcpServerId },
                    { LocalMCPServerTable.id }
                )
                .selectAll().where {
                    (SessionToolConfigTable.sessionId eq sessionId) and
                            (SessionToolConfigTable.isEnabled eq true) and
                            (ToolDefinitionTable.isEnabled eq true) and
                            ((LocalMCPServerTable.isEnabled eq true) or (ToolDefinitionTable.type neq ToolType.MCP_LOCAL))
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
                    if (enabled) {
                        // Upsert to enable the tool
                        SessionToolConfigTable.upsert {
                            it[SessionToolConfigTable.sessionId] = sessionId
                            it[SessionToolConfigTable.toolDefinitionId] = toolDefinitionId
                            it[isEnabled] = true
                        }
                    } else {
                        // Delete the entry to disable (saves space instead of storing isEnabled=false)
                        SessionToolConfigTable.deleteWhere {
                            (SessionToolConfigTable.sessionId eq sessionId) and
                                    (SessionToolConfigTable.toolDefinitionId eq toolDefinitionId)
                        }
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

    override suspend fun setToolsEnabledForSession(
        sessionId: Long,
        toolDefinitionIds: List<Long>,
        enabled: Boolean
    ): Either<SetToolsEnabledError, Unit> =
        transactionScope.transaction {
            either {
                if (toolDefinitionIds.isEmpty()) {
                    return@transaction Unit.right()
                }

                catch({
                    if (enabled) {
                        // Batch upsert all tools as enabled
                        SessionToolConfigTable.batchUpsert(
                            toolDefinitionIds,
                            SessionToolConfigTable.sessionId,
                            SessionToolConfigTable.toolDefinitionId
                        ) { toolDefinitionId ->
                            this[SessionToolConfigTable.sessionId] = sessionId
                            this[SessionToolConfigTable.toolDefinitionId] = toolDefinitionId
                            this[SessionToolConfigTable.isEnabled] = true
                        }
                    } else {
                        // Delete all matching entries
                        SessionToolConfigTable.deleteWhere {
                            (SessionToolConfigTable.sessionId eq sessionId) and
                                    (SessionToolConfigTable.toolDefinitionId inList toolDefinitionIds)
                        }
                    }
                }) { e: ExposedSQLException ->
                    ensure(!e.isForeignKeyViolation()) {
                        SetToolsEnabledError.ForeignKeyViolation(
                            "Foreign key constraint violation: sessionId=$sessionId, toolDefinitionIds=$toolDefinitionIds"
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

