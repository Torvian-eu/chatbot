package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.data.dao.error.ServerBuiltInToolDefinitionError

/**
 * Data Access Object for server built-in tool definitions.
 *
 * Manages the linkage between base tool definitions and the users that own them as server built-in
 * tools. The DAO performs joins with [eu.torvian.chatbot.server.data.tables.ToolDefinitionTable] to
 * return complete [ServerBuiltInToolDefinition] domain models.
 */
interface ServerBuiltInToolDefinitionDao {

    /**
     * Creates a linkage between a base tool definition and a user for a server built-in tool.
     *
     * @param toolDefinitionId The ID of the base tool definition row.
     * @param userId The ID of the owning user.
     * @param builtInToolName Canonical, unprefixed catalog name of the tool (e.g. `list_agent_roles`).
     * @return Either [ServerBuiltInToolDefinitionError] or Unit on success.
     */
    suspend fun insertTool(
        toolDefinitionId: Long,
        userId: Long,
        builtInToolName: String
    ): Either<ServerBuiltInToolDefinitionError, Unit>

    /**
     * Retrieves a server built-in tool definition by its base tool definition ID.
     *
     * @param toolDefinitionId The ID of the base tool definition.
     * @return Either [ServerBuiltInToolDefinitionError.NotFound] or the [ServerBuiltInToolDefinition].
     */
    suspend fun getToolById(
        toolDefinitionId: Long
    ): Either<ServerBuiltInToolDefinitionError.NotFound, ServerBuiltInToolDefinition>

    /**
     * Retrieves all server built-in tool definitions owned by a specific user.
     *
     * @param userId The owning user identifier.
     * @return List of [ServerBuiltInToolDefinition] (empty if none).
     */
    suspend fun getToolsByUserId(userId: Long): List<ServerBuiltInToolDefinition>

    /**
     * Updates the public name of a server built-in tool definition.
     *
     * Only the public [eu.torvian.chatbot.server.data.tables.ToolDefinitionTable.name] is changed;
     * the canonical [builtInToolName] is preserved. This is used when a user's tool-name prefix
     * changes, so dispatch keeps working via the untouched canonical name.
     *
     * @param toolDefinitionId The ID of the base tool definition.
     * @param publicName New public (possibly prefixed) tool name.
     * @return Either [ServerBuiltInToolDefinitionError.NotFound] or Unit on success.
     */
    suspend fun updatePublicName(
        toolDefinitionId: Long,
        publicName: String
    ): Either<ServerBuiltInToolDefinitionError.NotFound, Unit>
}
