package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.tool.CreateToolRequest
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing tool definitions and session-specific tool configurations.
 *
 * This repository serves as the single source of truth for tool data in the application,
 * providing reactive data streams through StateFlow and handling all tool-related operations.
 * It abstracts the underlying API layer and provides comprehensive error handling through
 * the RepositoryError hierarchy.
 *
 * The repository maintains an internal cache of tool data and automatically updates
 * all observers when changes occur, ensuring data consistency across the application.
 */
interface ToolRepository {

    /**
     * Reactive stream of all available tool definitions for the current user.
     *
     * This StateFlow provides real-time updates whenever the tool data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * Includes all global tools and user-specific MCP tools.
     *
     * @return StateFlow containing the current state of all user-accessible tools wrapped in DataState
     */
    val tools: StateFlow<DataState<RepositoryError, List<ToolDefinition>>>

    /**
     * Loads all tool definitions accessible to the current user from the backend.
     *
     * This operation fetches the latest tool data including:
     * - All global tools (non-MCP_LOCAL type)
     * - User-specific MCP tools (MCP servers owned by the current user)
     *
     * Updates the internal StateFlow with the fetched tools.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadTools(): Either<RepositoryError, Unit>

    /**
     * Retrieves a specific tool definition by ID.
     *
     * @param toolId The unique identifier of the tool to retrieve.
     * @return Either.Right with the ToolDefinition on success, or Either.Left with RepositoryError on failure
     */
    suspend fun getToolById(toolId: Long): Either<RepositoryError, ToolDefinition>

    /**
     * Creates a new tool definition.
     *
     * Upon successful creation, the new tool is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param request The tool creation request containing all necessary details
     * @return Either.Right with the created ToolDefinition on success, or Either.Left with RepositoryError on failure
     */
    suspend fun createTool(request: CreateToolRequest): Either<RepositoryError, ToolDefinition>

    /**
     * Updates an existing tool definition.
     *
     * Upon successful update, the modified tool replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param tool The updated tool object with the same ID as the existing tool
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun updateTool(tool: ToolDefinition): Either<RepositoryError, Unit>

    /**
     * Deletes a tool definition.
     *
     * Upon successful deletion, the tool is automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param toolId The unique identifier of the tool to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteTool(toolId: Long): Either<RepositoryError, Unit>

    /**
     * Loads the list of tools enabled for a specific session.
     *
     * This allows per-session tool configuration to be cached separately.
     *
     * @param sessionId The unique identifier of the session
     * @return Either.Right with a list of enabled ToolDefinition on success, or Either.Left with RepositoryError on failure
     */
    suspend fun loadEnabledToolsForSession(sessionId: Long): Either<RepositoryError, List<ToolDefinition>>

    /**
     * Gets a reactive stream of enabled tools for a specific session.
     *
     * The returned StateFlow will emit the current state of enabled tools for the session.
     * If the tools have not been loaded yet, call loadEnabledToolsForSession() first.
     *
     * @param sessionId The unique identifier of the session
     * @return A StateFlow containing the current state of enabled tools wrapped in DataState
     */
    suspend fun getEnabledToolsForSessionFlow(sessionId: Long): StateFlow<DataState<RepositoryError, List<ToolDefinition>>>

    /**
     * Enables or disables a tool for a specific session.
     *
     * Upon successful update, the session's tool configuration is updated in the cache,
     * triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session
     * @param toolDefinition The tool definition to enable/disable
     * @param enabled Whether to enable or disable the tool
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun setToolEnabledForSession(sessionId: Long, toolDefinition: ToolDefinition, enabled: Boolean): Either<RepositoryError, Unit>

    /**
     * Batch enables or disables multiple tools for a specific session.
     *
     * Upon successful update, the session's tool configuration is updated in the cache,
     * triggering updates to all observers.
     *
     * @param sessionId The unique identifier of the session
     * @param toolDefinitions The tool definitions to enable/disable
     * @param enabled Whether to enable or disable the tools
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun setToolsEnabledForSession(sessionId: Long, toolDefinitions: List<ToolDefinition>, enabled: Boolean): Either<RepositoryError, Unit>
}

