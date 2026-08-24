package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
import eu.torvian.chatbot.server.data.entities.ToolDefinitionEntity
import eu.torvian.chatbot.server.data.tables.BuiltInToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.LocalMCPServerTable
import eu.torvian.chatbot.server.data.tables.LocalMCPToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.OperatorToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ServerBuiltInToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.WorkersTable
import eu.torvian.chatbot.server.data.tables.mappers.toToolDefinition
import eu.torvian.chatbot.server.data.tables.mappers.toToolDefinitionEntity
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

/**
 * Exposed ORM implementation of [ToolDefinitionDao].
 *
 * Provides database operations for tool definitions using Exposed's SQL DSL.
 * All operations are wrapped in transactions managed by [TransactionScope].
 */
class ToolDefinitionDaoExposed(
    private val transactionScope: TransactionScope
) : ToolDefinitionDao {

    /**
     * Builds the joined query over [ToolDefinitionTable] that carries the columns required to
     * reconstruct a fully typed [ToolDefinition] via [toToolDefinition].
     *
     * LEFT JOINs the MCP, built-in, operator and server built-in linkage tables so that every tool
     * type (MCP_LOCAL, BUILTIN_WORKER, OPERATOR, BUILTIN_SERVER) can be mapped polymorphically
     * without a separate generic fallback type.
     */
    private fun joinedToolDefinitions() =
        ToolDefinitionTable
            .leftJoin(
                LocalMCPToolDefinitionTable,
                { ToolDefinitionTable.id },
                { LocalMCPToolDefinitionTable.toolDefinitionId })
            .leftJoin(LocalMCPServerTable, { LocalMCPToolDefinitionTable.mcpServerId }, { LocalMCPServerTable.id })
            .leftJoin(
                BuiltInToolDefinitionTable,
                { ToolDefinitionTable.id },
                { BuiltInToolDefinitionTable.toolDefinitionId }
            )
            .leftJoin(
                OperatorToolDefinitionTable,
                { ToolDefinitionTable.id },
                { OperatorToolDefinitionTable.toolDefinitionId }
            )
            .leftJoin(
                ServerBuiltInToolDefinitionTable,
                { ToolDefinitionTable.id },
                { ServerBuiltInToolDefinitionTable.toolDefinitionId }
            )

    override suspend fun getAllToolDefinitions(): List<ToolDefinition> =
        transactionScope.transaction {
            joinedToolDefinitions().selectAll().map { it.toToolDefinition() }
        }

    override suspend fun getToolDefinitionById(id: Long): Either<ToolDefinitionError.NotFound, ToolDefinition> =
        transactionScope.transaction {
            joinedToolDefinitions()
                .selectAll().where { ToolDefinitionTable.id eq id }
                .singleOrNull()
                ?.toToolDefinition()
                ?.right()
                ?: ToolDefinitionError.NotFound(id).left()
        }

    override suspend fun getToolDefinitionsByIds(ids: Collection<Long>): List<ToolDefinition> =
        transactionScope.transaction {
            if (ids.isEmpty()) {
                return@transaction emptyList()
            }
            joinedToolDefinitions()
                .selectAll()
                .where { ToolDefinitionTable.id inList ids }
                .map { it.toToolDefinition() }
        }

    override suspend fun getEnabledToolDefinitions(): List<ToolDefinition> =
        transactionScope.transaction {
            joinedToolDefinitions()
                .selectAll().where { ToolDefinitionTable.isEnabled eq true }
                .map { it.toToolDefinition() }
        }

    override suspend fun insertToolDefinition(
        name: String,
        description: String,
        type: ToolType,
        config: JsonObject,
        inputSchema: JsonObject,
        outputSchema: JsonObject?,
        isEnabled: Boolean
    ): ToolDefinitionEntity =
        transactionScope.transaction {
            val now = Clock.System.now().toEpochMilliseconds()
            ToolDefinitionTable.insert {
                it[ToolDefinitionTable.name] = name
                it[ToolDefinitionTable.description] = description
                it[ToolDefinitionTable.type] = type
                it[configJson] = config.toString()
                it[inputSchemaJson] = inputSchema.toString()
                it[outputSchemaJson] = outputSchema?.toString()
                it[ToolDefinitionTable.isEnabled] = isEnabled
                it[createdAt] = now
                it[updatedAt] = now
            }.resultedValues?.firstOrNull()?.toToolDefinitionEntity()
                ?: throw IllegalStateException("Failed to read inserted tool definition row for name $name")
        }

    override suspend fun updateToolDefinition(
        toolDefinition: ToolDefinition
    ): Either<ToolDefinitionError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val now = Clock.System.now().toEpochMilliseconds()
                val updatedRowCount = ToolDefinitionTable.update({ ToolDefinitionTable.id eq toolDefinition.id }) {
                    it[name] = toolDefinition.name
                    it[description] = toolDefinition.description
                    it[type] = toolDefinition.type
                    it[configJson] = toolDefinition.config.toString()
                    it[inputSchemaJson] = toolDefinition.inputSchema.toString()
                    it[outputSchemaJson] = toolDefinition.outputSchema?.toString()
                    it[isEnabled] = toolDefinition.isEnabled
                    it[updatedAt] = now
                }
                ensure(updatedRowCount != 0) { ToolDefinitionError.NotFound(toolDefinition.id) }
            }
        }

    override suspend fun deleteToolDefinition(id: Long): Either<ToolDefinitionError.NotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = ToolDefinitionTable.deleteWhere { ToolDefinitionTable.id eq id }
                ensure(deletedCount != 0) { ToolDefinitionError.NotFound(id) }
            }
        }

    override suspend fun getToolsForUser(userId: Long): List<ToolDefinition> =
        transactionScope.transaction {
            // Every tool is owned by exactly one principal (an MCP server, a worker, or a user), so
            // user access is resolved with four focused, owner-scoped INNER joins instead of one
            // big LEFT JOIN + OR filter. This also fixes the historical cross-user leak where a user
            // saw every non-MCP_LOCAL tool (including other users' built-in worker tools) because the
            // built-in ownership filter was missing entirely.
            val mcpTools = ToolDefinitionTable
                .innerJoin(LocalMCPToolDefinitionTable)
                .innerJoin(LocalMCPServerTable)
                .selectAll()
                .where { LocalMCPServerTable.userId eq userId }
                .map { it.toToolDefinition() }

            val builtInTools = ToolDefinitionTable
                .innerJoin(BuiltInToolDefinitionTable)
                .innerJoin(WorkersTable)
                .selectAll()
                .where { WorkersTable.ownerUserId eq userId }
                .map { it.toToolDefinition() }

            val operatorTools = ToolDefinitionTable
                .innerJoin(OperatorToolDefinitionTable)
                .selectAll()
                .where { OperatorToolDefinitionTable.userId eq userId }
                .map { it.toToolDefinition() }

            val serverBuiltInTools = ToolDefinitionTable
                .innerJoin(ServerBuiltInToolDefinitionTable)
                .selectAll()
                .where { ServerBuiltInToolDefinitionTable.userId eq userId }
                .map { it.toToolDefinition() }

            mcpTools + builtInTools + operatorTools + serverBuiltInTools
        }
}
