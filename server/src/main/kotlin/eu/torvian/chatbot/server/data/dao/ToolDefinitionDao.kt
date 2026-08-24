package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
import eu.torvian.chatbot.server.data.entities.ToolDefinitionEntity
import kotlinx.serialization.json.JsonObject

/**
 * Data Access Object for ToolDefinition entities.
 *
 * Provides CRUD operations and queries for tool definitions that can be used by LLM assistants.
 */
interface ToolDefinitionDao {
    /**
     * Retrieves all tool definitions, regardless of enabled status.
     *
     * @return List of all tool definitions in the database
     */
    suspend fun getAllToolDefinitions(): List<ToolDefinition>

    /**
     * Retrieves a single tool definition by ID.
     *
     * @param id The unique identifier of the tool definition
     * @return Either [ToolDefinitionError.NotFound] if not found, or the [ToolDefinition]
     */
    suspend fun getToolDefinitionById(id: Long): Either<ToolDefinitionError.NotFound, ToolDefinition>

    /**
     * Retrieves the tool definitions matching any of the given ids in a single query.
     *
     * Unlike [getToolDefinitionById], a partial result is normal (ids that do not resolve are simply
     * omitted), so there is no error surface.
     *
     * @param ids The tool-definition identifiers to load.
     * @return The resolved [ToolDefinition]s; ids that do not resolve are omitted.
     */
    suspend fun getToolDefinitionsByIds(ids: Collection<Long>): List<ToolDefinition>

    /**
     * Retrieves only globally enabled tool definitions.
     * Note: Session-specific enablement is handled separately in SessionToolConfigDao.
     *
     * @return List of enabled tool definitions
     */
    suspend fun getEnabledToolDefinitions(): List<ToolDefinition>

    /**
     * Creates a new tool definition row in the base table.
     *
     * @param name Unique identifier for the tool
     * @param description Human-readable explanation of the tool's purpose
     * @param type Category of tool
     * @param config Tool-specific configuration as JSON
     * @param inputSchema JSON Schema defining expected input parameters
     * @param outputSchema Optional JSON Schema defining expected output structure
     * @param isEnabled Whether this tool is globally available
     * @return The newly created [ToolDefinitionEntity]
     */
    suspend fun insertToolDefinition(
        name: String,
        description: String,
        type: ToolType,
        config: JsonObject,
        inputSchema: JsonObject,
        outputSchema: JsonObject?,
        isEnabled: Boolean
    ): ToolDefinitionEntity

    /**
     * Updates an existing tool definition with all fields from the provided entity.
     * This allows setting nullable fields back to null if needed.
     *
     * @param toolDefinition The complete tool definition entity with updated values
     * @return Either [ToolDefinitionError.NotFound] or Unit on success
     */
    suspend fun updateToolDefinition(
        toolDefinition: ToolDefinition
    ): Either<ToolDefinitionError.NotFound, Unit>

    /**
     * Deletes a tool definition.
     * Warning: CASCADE will delete all related ToolCall records and SessionToolConfig entries.
     *
     * @param id The unique identifier of the tool definition to delete
     * @return Either [ToolDefinitionError.NotFound] or Unit on success
     */
    suspend fun deleteToolDefinition(id: Long): Either<ToolDefinitionError.NotFound, Unit>

    /**
     * Retrieves all tools accessible to a specific user in a single SQL query.
     *
     * Returns a combination of:
     * - The user's own MCP_LOCAL tools (where the MCP server is owned by the user)
     * - Built-in worker tools of workers the user owns
     * - The user's own operator tools (e.g. spawn_agent)
     * - The user's own server built-in tools (e.g. list_agent_roles)
     *
     * Uses four focused, owner-scoped joins instead of one big LEFT JOIN + OR filter, so a user never
     * sees another user's tools (this fixed the historical cross-user leak for built-in worker tools).
     *
     * @param userId The ID of the user
     * @return List of ToolDefinition owned by the user
     */
    suspend fun getToolsForUser(userId: Long): List<ToolDefinition>
}
