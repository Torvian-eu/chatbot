package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.InsertToolError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPToolDefinitionError
import eu.torvian.chatbot.server.data.tables.LocalMCPServerTable
import eu.torvian.chatbot.server.data.tables.LocalMCPToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.mappers.toLocalMCPToolDefinition
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Exposed-based implementation of LocalMCPToolDefinitionDao.
 *
 * Manages the many-to-many relationship between MCP servers and tool definitions,
 * performing joins to return complete LocalMCPToolDefinition domain models.
 */
class LocalMCPToolDefinitionDaoExposed(
    private val transactionScope: TransactionScope
) : LocalMCPToolDefinitionDao {

    companion object {
        private val logger: Logger = LogManager.getLogger(LocalMCPToolDefinitionDaoExposed::class.java)
    }

    override suspend fun insertTool(
        toolDefinitionId: Long,
        mcpServerId: Long,
        mcpToolName: String
    ): Either<InsertToolError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    LocalMCPToolDefinitionTable.insert {
                        it[LocalMCPToolDefinitionTable.toolDefinitionId] = toolDefinitionId
                        it[LocalMCPToolDefinitionTable.mcpServerId] = mcpServerId
                        it[LocalMCPToolDefinitionTable.mcpToolName] = mcpToolName
                    }
                }) { e: ExposedSQLException ->
                    logger.error("Failed to create local MCP tool for tool $toolDefinitionId and server $mcpServerId: ${e.message}")
                    when {
                        e.isUniqueConstraintViolation() ->
                            raise(InsertToolError.DuplicateLinkage(toolDefinitionId))

                        e.isForeignKeyViolation() ->
                            raise(
                                InsertToolError.ReferencedEntityNotFound(
                                    toolDefinitionId = toolDefinitionId,
                                    mcpServerId = mcpServerId
                                )
                            )

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun getToolById(
        toolDefinitionId: Long
    ): Either<LocalMCPToolDefinitionError.NotFound, LocalMCPToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .innerJoin(LocalMCPToolDefinitionTable)
                .selectAll()
                .where { LocalMCPToolDefinitionTable.toolDefinitionId eq toolDefinitionId }
                .singleOrNull()
                ?.toLocalMCPToolDefinition()
                ?.right()
                ?: LocalMCPToolDefinitionError.NotFound(toolDefinitionId).left()
        }

    override suspend fun getToolsByServerId(mcpServerId: Long): List<LocalMCPToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .innerJoin(LocalMCPToolDefinitionTable)
                .selectAll()
                .where { LocalMCPToolDefinitionTable.mcpServerId eq mcpServerId }
                .map { it.toLocalMCPToolDefinition() }
        }

    override suspend fun getToolsForUser(userId: Long): List<LocalMCPToolDefinition> =
        transactionScope.transaction {
            // Join ToolDefinitionTable -> LocalMCPToolDefinitionTable -> LocalMCPServerTable
            // Filter by userId to get all tools from servers owned by this user
            ToolDefinitionTable
                .innerJoin(LocalMCPToolDefinitionTable)
                .innerJoin(LocalMCPServerTable)
                .selectAll()
                .where { LocalMCPServerTable.userId eq userId }
                .map { it.toLocalMCPToolDefinition() }
        }

    override suspend fun updateTool(
        toolDefinitionId: Long,
        mcpToolName: String
    ): Either<LocalMCPToolDefinitionError.NotFound, Unit> =
        transactionScope.transaction {
            val updatedCount = LocalMCPToolDefinitionTable.update(
                where = { LocalMCPToolDefinitionTable.toolDefinitionId eq toolDefinitionId }
            ) {
                it[LocalMCPToolDefinitionTable.mcpToolName] = mcpToolName
            }
            if (updatedCount > 0) {
                Unit.right()
            } else {
                LocalMCPToolDefinitionError.NotFound(toolDefinitionId).left()
            }
        }

    override suspend fun deleteToolsByServerId(mcpServerId: Long): Int =
        transactionScope.transaction {
            // Delete from ToolDefinitionTable (cascade will handle junction table)
            ToolDefinitionTable.deleteWhere {
                ToolDefinitionTable.id inSubQuery LocalMCPToolDefinitionTable
                    .select(LocalMCPToolDefinitionTable.toolDefinitionId)
                    .where { LocalMCPToolDefinitionTable.mcpServerId eq mcpServerId }
            }
        }
}
