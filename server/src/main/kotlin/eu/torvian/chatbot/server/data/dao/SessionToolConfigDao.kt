package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.data.dao.error.ClearToolConfigError
import eu.torvian.chatbot.server.data.dao.error.SetToolEnabledError
import eu.torvian.chatbot.server.data.dao.error.SetToolsEnabledError

/**
 * Data Access Object for SessionToolConfig entities.
 *
 * Manages session-specific tool configurations, allowing users to enable/disable
 * specific tools for individual chat sessions.
 */
interface SessionToolConfigDao {
    /**
     * Retrieves all tools that are enabled for a specific session.
     * Checks BOTH global enabled flag AND session-specific flag.
     * Implementation: JOIN ToolDefinitionTable with SessionToolConfigTable.
     *
     * @param sessionId The ID of the session
     * @return List of enabled tool definitions for the session
     */
    suspend fun getEnabledToolsForSession(sessionId: Long): List<ToolDefinition>

    /**
     * Checks if a specific tool is enabled for a session.
     * Returns false if no config row exists or if isEnabled is false.
     *
     * @param sessionId The ID of the session
     * @param toolDefinitionId The ID of the tool definition
     * @return true if the tool is enabled for the session, false otherwise
     */
    suspend fun isToolEnabledForSession(sessionId: Long, toolDefinitionId: Long): Boolean

    /**
     * Enables or disables a tool for a specific session (upsert operation).
     * Will INSERT if no row exists, UPDATE if it does.
     *
     * @param sessionId The ID of the session
     * @param toolDefinitionId The ID of the tool definition
     * @param enabled Whether the tool should be enabled for this session
     * @return Either [SetToolEnabledError] or Unit on success
     */
    suspend fun setToolEnabledForSession(
        sessionId: Long,
        toolDefinitionId: Long,
        enabled: Boolean
    ): Either<SetToolEnabledError, Unit>

    /**
     * Batch enables or disables multiple tools for a specific session (upsert operation).
     * Will INSERT if no rows exist, UPDATE if they do.
     * More efficient than calling setToolEnabledForSession multiple times.
     *
     * @param sessionId The ID of the session
     * @param toolDefinitionIds The IDs of the tool definitions
     * @param enabled Whether the tools should be enabled for this session
     * @return Either [SetToolsEnabledError] or Unit on success
     */
    suspend fun setToolsEnabledForSession(
        sessionId: Long,
        toolDefinitionIds: List<Long>,
        enabled: Boolean
    ): Either<SetToolsEnabledError, Unit>

    /**
     * Returns all session IDs that have this tool enabled.
     * Useful for understanding tool usage across sessions.
     *
     * @param toolDefinitionId The ID of the tool definition
     * @return List of session IDs that have this tool enabled
     */
    suspend fun getSessionsUsingTool(toolDefinitionId: Long): List<Long>

    /**
     * Removes all tool configurations for a session.
     * Useful when resetting a session.
     *
     * @param sessionId The ID of the session
     * @return Either [ClearToolConfigError] or Unit on success
     */
    suspend fun clearSessionToolConfig(sessionId: Long): Either<ClearToolConfigError, Unit>
}
