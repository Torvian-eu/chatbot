package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.MiscToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.error.ToolDefinitionError
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
    suspend fun getAllToolDefinitions(): List<MiscToolDefinition>

    /**
     * Retrieves a single tool definition by ID.
     *
     * @param id The unique identifier of the tool definition
     * @return Either [ToolDefinitionError.NotFound] if not found, or the [MiscToolDefinition]
     */
    suspend fun getToolDefinitionById(id: Long): Either<ToolDefinitionError.NotFound, MiscToolDefinition>

    /**
     * Retrieves a tool definition by its unique name.
     * Use this when you have the tool name from LLM API responses.
     *
     * @param name The unique name of the tool
     * @return Either [ToolDefinitionError.NameNotFound] if not found, or the [MiscToolDefinition]
     */
    suspend fun getToolDefinitionByName(name: String): Either<ToolDefinitionError.NameNotFound, ToolDefinition>

    /**
     * Retrieves only globally enabled tool definitions.
     * Note: Session-specific enablement is handled separately in SessionToolConfigDao.
     *
     * @return List of enabled tool definitions
     */
    suspend fun getEnabledToolDefinitions(): List<MiscToolDefinition>

    /**
     * Creates a new tool definition.
     *
     * @param name Unique identifier for the tool
     * @param description Human-readable explanation of the tool's purpose
     * @param type Category of tool
     * @param config Tool-specific configuration as JSON
     * @param inputSchema JSON Schema defining expected input parameters
     * @param outputSchema Optional JSON Schema defining expected output structure
     * @param isEnabled Whether this tool is globally available
     * @return The newly created [MiscToolDefinition]
     */
    suspend fun insertToolDefinition(
        name: String,
        description: String,
        type: ToolType,
        config: JsonObject,
        inputSchema: JsonObject,
        outputSchema: JsonObject?,
        isEnabled: Boolean
    ): MiscToolDefinition

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
     * - All global tools (non-MCP_LOCAL type)
     * - User-specific MCP_LOCAL tools (where the MCP server is owned by the user)
     *
     * Uses a LEFT JOIN with LocalMCPToolDefinitionTable and LocalMCPServerTable to fetch
     * all tools in one database query for efficiency.
     *
     * @param userId The ID of the user
     * @return List of ToolDefinition (mix of MiscToolDefinition and LocalMCPToolDefinition)
     */
    suspend fun getToolsForUser(userId: Long): List<ToolDefinition>
}
