package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.server.data.dao.BuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.BuiltInToolDefinitionError
import eu.torvian.chatbot.server.data.tables.BuiltInToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.mappers.toBuiltInWorkerToolDefinition
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Exposed-based implementation of [BuiltInToolDefinitionDao].
 *
 * Manages the linkage between base tool definitions and workers for built-in tools, performing
 * joins to return complete [BuiltInWorkerToolDefinition] domain models.
 */
class BuiltInToolDefinitionDaoExposed(
    private val transactionScope: TransactionScope
) : BuiltInToolDefinitionDao {

    companion object {
        private val logger: Logger = LogManager.getLogger(BuiltInToolDefinitionDaoExposed::class.java)
    }

    override suspend fun insertTool(
        toolDefinitionId: Long,
        workerId: Long,
        builtInToolName: String
    ): Either<BuiltInToolDefinitionError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    BuiltInToolDefinitionTable.insert {
                        it[BuiltInToolDefinitionTable.toolDefinitionId] = toolDefinitionId
                        it[BuiltInToolDefinitionTable.workerId] = workerId
                        it[BuiltInToolDefinitionTable.builtInToolName] = builtInToolName
                    }
                }) { e: ExposedSQLException ->
                    logger.error("Failed to create built-in tool linkage for tool $toolDefinitionId and worker $workerId: ${e.message}")
                    when {
                        e.isUniqueConstraintViolation() ->
                            raise(BuiltInToolDefinitionError.DuplicateLinkage(toolDefinitionId))

                        e.isForeignKeyViolation() ->
                            raise(
                                BuiltInToolDefinitionError.ReferencedEntityNotFound(
                                    toolDefinitionId = toolDefinitionId,
                                    workerId = workerId,
                                    message = "Tool definition or worker does not exist"
                                )
                            )

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun getToolById(
        toolDefinitionId: Long
    ): Either<BuiltInToolDefinitionError.NotFound, BuiltInWorkerToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .innerJoin(BuiltInToolDefinitionTable)
                .selectAll()
                .where { BuiltInToolDefinitionTable.toolDefinitionId eq toolDefinitionId }
                .singleOrNull()
                ?.toBuiltInWorkerToolDefinition()
                ?.right()
                ?: BuiltInToolDefinitionError.NotFound(toolDefinitionId).left()
        }

    override suspend fun getToolsByWorkerId(workerId: Long): List<BuiltInWorkerToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .innerJoin(BuiltInToolDefinitionTable)
                .selectAll()
                .where { BuiltInToolDefinitionTable.workerId eq workerId }
                .map { it.toBuiltInWorkerToolDefinition() }
        }

    override suspend fun updatePublicName(
        toolDefinitionId: Long,
        publicName: String
    ): Either<BuiltInToolDefinitionError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                // Guard: the linkage must exist for this to be a built-in tool rename.
                val linkageExists = BuiltInToolDefinitionTable
                    .selectAll()
                    .where { BuiltInToolDefinitionTable.toolDefinitionId eq toolDefinitionId }
                    .singleOrNull() != null
                ensure(linkageExists) { BuiltInToolDefinitionError.NotFound(toolDefinitionId) }

                ToolDefinitionTable.update({ ToolDefinitionTable.id eq toolDefinitionId }) {
                    it[name] = publicName
                }
                Unit.right()
            }
        }

    override suspend fun deleteToolsByWorkerId(workerId: Long): Int =
        transactionScope.transaction {
            // Delete base tool definition rows; the linkage cascades on tool_definition_id.
            ToolDefinitionTable.deleteWhere {
                ToolDefinitionTable.id inSubQuery BuiltInToolDefinitionTable
                    .select(BuiltInToolDefinitionTable.toolDefinitionId)
                    .where { BuiltInToolDefinitionTable.workerId eq workerId }
            }
        }
}
