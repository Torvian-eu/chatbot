package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.data.dao.ServerBuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.ServerBuiltInToolDefinitionError
import eu.torvian.chatbot.server.data.tables.ServerBuiltInToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.mappers.toServerBuiltInToolDefinition
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed-based implementation of [ServerBuiltInToolDefinitionDao].
 *
 * Manages the linkage between base tool definitions and users for server built-in tools, performing
 * joins to return complete [ServerBuiltInToolDefinition] domain models.
 */
class ServerBuiltInToolDefinitionDaoExposed(
    private val transactionScope: TransactionScope
) : ServerBuiltInToolDefinitionDao {

    companion object {
        private val logger: Logger = LogManager.getLogger(ServerBuiltInToolDefinitionDaoExposed::class.java)
    }

    override suspend fun insertTool(
        toolDefinitionId: Long,
        userId: Long
    ): Either<ServerBuiltInToolDefinitionError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    ServerBuiltInToolDefinitionTable.insert {
                        it[ServerBuiltInToolDefinitionTable.toolDefinitionId] = toolDefinitionId
                        it[ServerBuiltInToolDefinitionTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    logger.error(
                        "Failed to create server built-in tool linkage for tool $toolDefinitionId " +
                            "and user $userId: ${e.message}"
                    )
                    when {
                        e.isUniqueConstraintViolation() ->
                            raise(ServerBuiltInToolDefinitionError.DuplicateLinkage(toolDefinitionId))

                        e.isForeignKeyViolation() ->
                            raise(
                                ServerBuiltInToolDefinitionError.ReferencedEntityNotFound(
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
    ): Either<ServerBuiltInToolDefinitionError.NotFound, ServerBuiltInToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .innerJoin(ServerBuiltInToolDefinitionTable)
                .selectAll()
                .where { ServerBuiltInToolDefinitionTable.toolDefinitionId eq toolDefinitionId }
                .singleOrNull()
                ?.toServerBuiltInToolDefinition()
                ?.right()
                ?: ServerBuiltInToolDefinitionError.NotFound(toolDefinitionId).left()
        }

    override suspend fun getToolsByUserId(userId: Long): List<ServerBuiltInToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .innerJoin(ServerBuiltInToolDefinitionTable)
                .selectAll()
                .where { ServerBuiltInToolDefinitionTable.userId eq userId }
                .map { it.toServerBuiltInToolDefinition() }
        }
}
