package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.server.data.dao.OperatorToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.OperatorToolDefinitionError
import eu.torvian.chatbot.server.data.tables.OperatorToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.mappers.toOperatorToolDefinition
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed-based implementation of [OperatorToolDefinitionDao].
 *
 * Manages the linkage between base tool definitions and users for operator tools, performing joins to
 * return complete [OperatorToolDefinition] domain models.
 */
class OperatorToolDefinitionDaoExposed(
    private val transactionScope: TransactionScope
) : OperatorToolDefinitionDao {

    companion object {
        private val logger: Logger = LogManager.getLogger(OperatorToolDefinitionDaoExposed::class.java)
    }

    override suspend fun insertTool(
        toolDefinitionId: Long,
        userId: Long
    ): Either<OperatorToolDefinitionError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    OperatorToolDefinitionTable.insert {
                        it[OperatorToolDefinitionTable.toolDefinitionId] = toolDefinitionId
                        it[OperatorToolDefinitionTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    logger.error("Failed to create operator tool linkage for tool $toolDefinitionId and user $userId: ${e.message}")
                    when {
                        e.isUniqueConstraintViolation() ->
                            raise(OperatorToolDefinitionError.DuplicateLinkage(toolDefinitionId))

                        e.isForeignKeyViolation() ->
                            raise(
                                OperatorToolDefinitionError.ReferencedEntityNotFound(
                                    toolDefinitionId = toolDefinitionId,
                                    userId = userId,
                                    message = "Tool definition or user does not exist"
                                )
                            )

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun getToolById(
        toolDefinitionId: Long
    ): Either<OperatorToolDefinitionError.NotFound, OperatorToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .innerJoin(OperatorToolDefinitionTable)
                .selectAll()
                .where { OperatorToolDefinitionTable.toolDefinitionId eq toolDefinitionId }
                .singleOrNull()
                ?.toOperatorToolDefinition()
                ?.right()
                ?: OperatorToolDefinitionError.NotFound(toolDefinitionId).left()
        }

    override suspend fun getToolsByUserId(userId: Long): List<OperatorToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .innerJoin(OperatorToolDefinitionTable)
                .selectAll()
                .where { OperatorToolDefinitionTable.userId eq userId }
                .map { it.toOperatorToolDefinition() }
        }
}
