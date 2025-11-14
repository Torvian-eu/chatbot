package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.data.dao.ToolCallDao
import eu.torvian.chatbot.server.data.dao.error.DeleteToolCallError
import eu.torvian.chatbot.server.data.dao.error.InsertToolCallError
import eu.torvian.chatbot.server.data.dao.error.ToolCallError
import eu.torvian.chatbot.server.data.dao.error.UpdateToolCallError
import eu.torvian.chatbot.server.data.tables.ChatMessageTable
import eu.torvian.chatbot.server.data.tables.ToolCallTable
import eu.torvian.chatbot.server.data.tables.mappers.toToolCall
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.datetime.Instant
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Exposed ORM implementation of [ToolCallDao].
 *
 * Provides database operations for tool call execution records using Exposed's SQL DSL.
 * All operations are wrapped in transactions managed by [TransactionScope].
 */
class ToolCallDaoExposed(
    private val transactionScope: TransactionScope
) : ToolCallDao {

    override suspend fun getToolCallsByMessageId(messageId: Long): List<ToolCall> =
        transactionScope.transaction {
            ToolCallTable
                .selectAll().where { ToolCallTable.messageId eq messageId }
                .map { it.toToolCall() }
        }

    override suspend fun getToolCallsBySessionId(sessionId: Long): List<ToolCall> =
        transactionScope.transaction {
            ToolCallTable
                .innerJoin(ChatMessageTable, { messageId }, { ChatMessageTable.id })
                .selectAll().where { ChatMessageTable.sessionId eq sessionId }
                .map { it.toToolCall() }
        }

    override suspend fun getToolCallById(id: Long): Either<ToolCallError.NotFound, ToolCall> =
        transactionScope.transaction {
            ToolCallTable
                .selectAll().where { ToolCallTable.id eq id }
                .singleOrNull()
                ?.toToolCall()
                ?.right()
                ?: ToolCallError.NotFound(id).left()
        }

    override suspend fun insertToolCall(
        messageId: Long,
        toolDefinitionId: Long?,
        toolName: String,
        toolCallId: String?,
        input: String?,
        output: String?,
        status: ToolCallStatus,
        errorMessage: String?,
        executedAt: Instant,
        durationMs: Long?
    ): Either<InsertToolCallError, ToolCall> =
        transactionScope.transaction {
            either {
                catch({
                    val insertStatement = ToolCallTable.insert {
                        it[ToolCallTable.messageId] = messageId
                        it[ToolCallTable.toolDefinitionId] = toolDefinitionId
                        it[ToolCallTable.toolName] = toolName
                        it[ToolCallTable.toolCallId] = toolCallId
                        it[ToolCallTable.inputJson] = input
                        it[ToolCallTable.outputJson] = output
                        it[ToolCallTable.status] = status
                        it[ToolCallTable.errorMessage] = errorMessage
                        it[ToolCallTable.executedAt] = executedAt.toEpochMilliseconds()
                        it[ToolCallTable.durationMs] = durationMs
                    }
                    insertStatement.resultedValues?.first()?.toToolCall()
                        ?: throw IllegalStateException("Failed to retrieve newly inserted tool call")
                }) { e: ExposedSQLException ->
                    ensure(!e.isForeignKeyViolation()) {
                        InsertToolCallError.ForeignKeyViolation(
                            "Foreign key constraint violation: messageId=$messageId, toolDefinitionId=$toolDefinitionId"
                        )
                    }
                    throw e
                }
            }
        }

    override suspend fun updateToolCall(
        toolCall: ToolCall
    ): Either<UpdateToolCallError, Unit> =
        transactionScope.transaction {
            either {
                val updatedRowCount = ToolCallTable.update({ ToolCallTable.id eq toolCall.id }) {
                    it[messageId] = toolCall.messageId
                    it[toolDefinitionId] = toolCall.toolDefinitionId
                    it[toolName] = toolCall.toolName
                    it[toolCallId] = toolCall.toolCallId
                    it[inputJson] = toolCall.input
                    it[outputJson] = toolCall.output
                    it[status] = toolCall.status
                    it[errorMessage] = toolCall.errorMessage
                    it[executedAt] = toolCall.executedAt.toEpochMilliseconds()
                    it[durationMs] = toolCall.durationMs
                }
                ensure(updatedRowCount != 0) { UpdateToolCallError.NotFound(toolCall.id) }
            }
        }

    override suspend fun deleteToolCallsByMessageId(messageId: Long): Either<DeleteToolCallError, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = ToolCallTable.deleteWhere { ToolCallTable.messageId eq messageId }
                ensure(deletedCount != 0) { DeleteToolCallError.NotFound(messageId) }
            }
        }
}

