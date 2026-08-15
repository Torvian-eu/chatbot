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
import eu.torvian.chatbot.server.data.tables.ToolDefinitionTable
import eu.torvian.chatbot.server.data.tables.mappers.toToolDefinition
import eu.torvian.chatbot.server.data.tables.mappers.toToolDefinitionEntity
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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

    /**
     * Builds the joined query over [ToolDefinitionTable] that carries the columns required to
     * reconstruct a fully typed [ToolDefinition] via [toToolDefinition].
     *
     * LEFT JOINs the MCP and built-in linkage tables so that every tool type (MCP_LOCAL,
     * BUILTIN_WORKER, and any future generic type) can be mapped polymorphically without a
     * separate generic fallback type.
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

    override suspend fun getToolDefinitionByName(name: String): Either<ToolDefinitionError.NameNotFound, ToolDefinition> =
        transactionScope.transaction {
            joinedToolDefinitions()
                .selectAll().where { ToolDefinitionTable.name eq name }
                .singleOrNull()
                ?.toToolDefinition()
                ?.right()
                ?: ToolDefinitionError.NameNotFound(name).left()
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
            // LEFT JOIN LocalMCPToolDefinitionTable and LocalMCPServerTable to get all tools
            // Returns global tools (non-MCP_LOCAL) and user-specific MCP tools in one query
            val joinedQuery = ToolDefinitionTable
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
