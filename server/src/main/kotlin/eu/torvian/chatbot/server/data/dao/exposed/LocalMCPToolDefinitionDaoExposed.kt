package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.CreateLinkageError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPToolDefinitionError
import eu.torvian.chatbot.server.data.tables.LocalMCPToolDefinitionTable
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/**
 * Exposed-based implementation of LocalMCPToolDefinitionDao.
 *
 * Manages the junction table linking MCP servers to their tool definitions.
 */
class LocalMCPToolDefinitionDaoExposed(
    private val transactionScope: TransactionScope
) : LocalMCPToolDefinitionDao {

    companion object {
        private val logger: Logger = LogManager.getLogger(LocalMCPToolDefinitionDaoExposed::class.java)
    }

    override suspend fun createLinkage(
        toolDefinitionId: Long,
        mcpServerId: Long,
        mcpToolName: String?
    ): Either<CreateLinkageError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    LocalMCPToolDefinitionTable.insert {
                        it[LocalMCPToolDefinitionTable.toolDefinitionId] = toolDefinitionId
                        it[LocalMCPToolDefinitionTable.mcpServerId] = mcpServerId
                        it[LocalMCPToolDefinitionTable.mcpToolName] = mcpToolName
                    }
                }) { e: ExposedSQLException ->
                    logger.error("Failed to create linkage between tool $toolDefinitionId and server $mcpServerId: ${e.message}")
                    when {
                        e.isUniqueConstraintViolation() ->
                            raise(CreateLinkageError.DuplicateLinkage(toolDefinitionId, mcpServerId))

                        e.isForeignKeyViolation() ->
                            raise(
                                CreateLinkageError.ReferencedEntityNotFound(
                                    toolDefinitionId = toolDefinitionId,
                                    mcpServerId = mcpServerId
                                )
                            )

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun getToolIdsByServerId(mcpServerId: Long): List<Long> =
        transactionScope.transaction {
            LocalMCPToolDefinitionTable
                .selectAll()
                .where { LocalMCPToolDefinitionTable.mcpServerId eq mcpServerId }
                .map { it[LocalMCPToolDefinitionTable.toolDefinitionId].value }
        }

    override suspend fun getServerIdByToolId(
        toolDefinitionId: Long
    ): Either<LocalMCPToolDefinitionError.NotFound, Long> =
        transactionScope.transaction {
            LocalMCPToolDefinitionTable
                .selectAll()
                .where { LocalMCPToolDefinitionTable.toolDefinitionId eq toolDefinitionId }
                .singleOrNull()
                ?.let { it[LocalMCPToolDefinitionTable.mcpServerId].value.right() }
                ?: LocalMCPToolDefinitionError.NotFound(toolDefinitionId).left()
        }

    override suspend fun deleteLinkage(
        toolDefinitionId: Long,
        mcpServerId: Long
    ): Either<LocalMCPToolDefinitionError.NotFound, Unit> =
        transactionScope.transaction {
            val deletedCount = LocalMCPToolDefinitionTable.deleteWhere {
                (LocalMCPToolDefinitionTable.toolDefinitionId eq toolDefinitionId) and
                        (LocalMCPToolDefinitionTable.mcpServerId eq mcpServerId)
            }
            if (deletedCount > 0) {
                Unit.right()
            } else {
                LocalMCPToolDefinitionError.NotFound(toolDefinitionId, mcpServerId).left()
            }
        }

    override suspend fun deleteAllLinkagesForServer(mcpServerId: Long): Int =
        transactionScope.transaction {
            LocalMCPToolDefinitionTable.deleteWhere {
                LocalMCPToolDefinitionTable.mcpServerId eq mcpServerId
            }
        }

    override suspend fun isLinked(toolDefinitionId: Long, mcpServerId: Long): Boolean =
        transactionScope.transaction {
            LocalMCPToolDefinitionTable
                .selectAll()
                .where {
                    (LocalMCPToolDefinitionTable.toolDefinitionId eq toolDefinitionId) and
                            (LocalMCPToolDefinitionTable.mcpServerId eq mcpServerId)
                }
                .count() > 0
        }

    override suspend fun getMcpToolName(toolDefinitionId: Long): Either<LocalMCPToolDefinitionError.NotFound, String?> =
        transactionScope.transaction {
            LocalMCPToolDefinitionTable
                .selectAll()
                .where { LocalMCPToolDefinitionTable.toolDefinitionId eq toolDefinitionId }
                .singleOrNull()
                ?.let { it[LocalMCPToolDefinitionTable.mcpToolName].right() }
                ?: LocalMCPToolDefinitionError.NotFound(toolDefinitionId).left()
        }
}

