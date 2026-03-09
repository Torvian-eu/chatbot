package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.MiscToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
import eu.torvian.chatbot.server.data.tables.LocalMCPServerTable
import eu.torvian.chatbot.server.data.tables.LocalMCPToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.mappers.toMiscToolDefinition
import eu.torvian.chatbot.server.data.tables.mappers.toToolDefinition
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
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

    override suspend fun getAllToolDefinitions(): List<MiscToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable.selectAll().map { it.toMiscToolDefinition() }
        }

    override suspend fun getToolDefinitionById(id: Long): Either<ToolDefinitionError.NotFound, MiscToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .selectAll().where { ToolDefinitionTable.id eq id }
                .singleOrNull()
                ?.toMiscToolDefinition()
                ?.right()
                ?: ToolDefinitionError.NotFound(id).left()
        }

    override suspend fun getToolDefinitionByName(name: String): Either<ToolDefinitionError.NameNotFound, MiscToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .selectAll().where { ToolDefinitionTable.name eq name }
                .singleOrNull()
                ?.toMiscToolDefinition()
                ?.right()
                ?: ToolDefinitionError.NameNotFound(name).left()
        }

    override suspend fun getEnabledToolDefinitions(): List<MiscToolDefinition> =
        transactionScope.transaction {
            ToolDefinitionTable
                .selectAll().where { ToolDefinitionTable.isEnabled eq true }
                .map { it.toMiscToolDefinition() }
        }

    override suspend fun insertToolDefinition(
        name: String,
        description: String,
        type: ToolType,
        config: JsonObject,
        inputSchema: JsonObject,
        outputSchema: JsonObject?,
        isEnabled: Boolean
    ): MiscToolDefinition =
        transactionScope.transaction {
            val now = Clock.System.now().toEpochMilliseconds()
            val insertStatement = ToolDefinitionTable.insert {
                it[ToolDefinitionTable.name] = name
                it[ToolDefinitionTable.description] = description
                it[ToolDefinitionTable.type] = type
                it[configJson] = config.toString()
                it[inputSchemaJson] = inputSchema.toString()
                it[outputSchemaJson] = outputSchema?.toString()
                it[ToolDefinitionTable.isEnabled] = isEnabled
                it[createdAt] = now
                it[updatedAt] = now
            }
            insertStatement.resultedValues?.first()?.toMiscToolDefinition()
                ?: throw IllegalStateException("Failed to retrieve newly inserted tool definition")
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
            // LEFT JOIN LocalMCPToolDefinitionTable and LocalMCPServerTable to get all tools
            // Returns global tools (non-MCP_LOCAL) and user-specific MCP tools in one query
            val joinedQuery = ToolDefinitionTable
                .leftJoin(
                    LocalMCPToolDefinitionTable,
                    { ToolDefinitionTable.id },
                    { LocalMCPToolDefinitionTable.toolDefinitionId })
                .leftJoin(LocalMCPServerTable, { LocalMCPToolDefinitionTable.mcpServerId }, { LocalMCPServerTable.id })

            joinedQuery
                .selectAll()
                .where {
                    // Include global tools OR user-specific MCP tools
                    not(ToolDefinitionTable.type eq ToolType.MCP_LOCAL) or
                            (LocalMCPServerTable.userId eq userId)
                }
                .map { it.toToolDefinition() }
        }
}

