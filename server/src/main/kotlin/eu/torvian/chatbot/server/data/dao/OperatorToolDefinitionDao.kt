package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.server.data.dao.error.OperatorToolDefinitionError

/**
 * Data Access Object for operator tool definitions.
 *
 * Manages the linkage between base tool definitions and the users that own them as operator tools.
 * The DAO performs joins with [eu.torvian.chatbot.server.data.tables.ToolDefinitionTable] to return
 * complete [OperatorToolDefinition] domain models.
 */
interface OperatorToolDefinitionDao {

    /**
     * Creates a linkage between a base tool definition and a user for an operator tool.
     *
     * @param toolDefinitionId The ID of the base tool definition row.
     * @param userId The ID of the owning user.
     * @return Either [OperatorToolDefinitionError] or Unit on success.
     */
    suspend fun insertTool(
        toolDefinitionId: Long,
        userId: Long
    ): Either<OperatorToolDefinitionError, Unit>

    /**
     * Retrieves an operator tool definition by its base tool definition ID.
     *
     * @param toolDefinitionId The ID of the base tool definition.
     * @return Either [OperatorToolDefinitionError.NotFound] or the [OperatorToolDefinition].
     */
    suspend fun getToolById(
        toolDefinitionId: Long
    ): Either<OperatorToolDefinitionError.NotFound, OperatorToolDefinition>

    /**
     * Retrieves all operator tool definitions owned by a specific user.
     *
     * @param userId The owning user identifier.
     * @return List of [OperatorToolDefinition] (empty if none).
     */
    suspend fun getToolsByUserId(userId: Long): List<OperatorToolDefinition>
}
