package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.server.data.dao.error.BuiltInToolDefinitionError

/**
 * Data Access Object for built-in worker tool definitions.
 *
 * Manages the linkage between base tool definitions and the workers that expose them as built-in
 * tools. The DAO performs joins with [ToolDefinitionTable] to return complete
 * [BuiltInWorkerToolDefinition] domain models.
 */
interface BuiltInToolDefinitionDao {
    /**
     * Creates a linkage between a base tool definition and a worker for a built-in tool.
     *
     * @param toolDefinitionId The ID of the base tool definition row.
     * @param workerId The ID of the owning worker.
     * @param builtInToolName Unprefixed internal worker runtime name.
     * @return Either [BuiltInToolDefinitionError] or Unit on success.
     */
    suspend fun insertTool(
        toolDefinitionId: Long,
        workerId: Long,
        builtInToolName: String
    ): Either<BuiltInToolDefinitionError, Unit>

    /**
     * Retrieves a built-in worker tool definition by its base tool definition ID.
     *
     * @param toolDefinitionId The ID of the base tool definition.
     * @return Either [BuiltInToolDefinitionError.NotFound] or the [BuiltInWorkerToolDefinition].
     */
    suspend fun getToolById(
        toolDefinitionId: Long
    ): Either<BuiltInToolDefinitionError.NotFound, BuiltInWorkerToolDefinition>

    /**
     * Retrieves all built-in worker tool definitions owned by a specific worker.
     *
     * @param workerId The owning worker identifier.
     * @return List of [BuiltInWorkerToolDefinition] (empty if none).
     */
    suspend fun getToolsByWorkerId(workerId: Long): List<BuiltInWorkerToolDefinition>

    /**
     * Updates the public name of a built-in worker tool definition.
     *
     * Only the public [ToolDefinitionTable.name] is changed; the unprefixed [builtInToolName] is
     * preserved. This is used when a worker's tool-name prefix changes.
     *
     * @param toolDefinitionId The ID of the base tool definition.
     * @param publicName New public (possibly prefixed) tool name.
     * @return Either [BuiltInToolDefinitionError.NotFound] or Unit on success.
     */
    suspend fun updatePublicName(
        toolDefinitionId: Long,
        publicName: String
    ): Either<BuiltInToolDefinitionError.NotFound, Unit>

    /**
     * Deletes all built-in worker tool definitions owned by a worker.
     *
     * @param workerId The owning worker identifier.
     * @return Number of tool definitions deleted.
     */
    suspend fun deleteToolsByWorkerId(workerId: Long): Int
}
