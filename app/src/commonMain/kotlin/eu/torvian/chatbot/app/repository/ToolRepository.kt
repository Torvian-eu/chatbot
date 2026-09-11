package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing tool definitions and user tool approval preferences.
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
     * Reactive stream of the current user's tool approval preferences.
     *
     * This StateFlow provides real-time updates whenever the preferences change,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of user tool approval preferences wrapped in DataState
     */
    val toolApprovalPreferences: StateFlow<DataState<RepositoryError, List<UserToolApprovalPreference>>>

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
     * Loads the current user's tool approval preferences from the backend.
     *
     * This operation fetches the latest tool approval preferences and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadUserToolApprovalPreferences(): Either<RepositoryError, Unit>

    /**
     * Sets or updates an auto-approval preference for a specific tool and user.
     *
     * @param toolDefinitionId The ID of the tool definition
     * @param autoApprove Whether to auto-approve (true) or auto-deny (false) tool calls
     * @param conditions Optional JSON string for conditional approval logic (reserved for future use)
     * @param denialReason Optional reason text for auto-denials (reserved for future use)
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun setToolApprovalPreference(
        toolDefinitionId: Long,
        autoApprove: Boolean,
        conditions: String? = null,
        denialReason: String? = null
    ): Either<RepositoryError, Unit>

    /**
     * Deletes an approval preference for a specific tool and user.
     *
     * @param toolDefinitionId The ID of the tool definition
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteToolApprovalPreference(toolDefinitionId: Long): Either<RepositoryError, Unit>

    /**
     * Applies a transformation to the in-memory tools cache.
     *
     * @param update A function that takes the current list of tools and returns an updated list.
     */
    suspend fun updateToolCache(update: (List<ToolDefinition>) -> List<ToolDefinition>)

    /**
     * Applies a transformation to the in-memory tool approval preferences cache.
     *
     * @param update A function that takes the current list of tool approval preferences and returns an updated list.
     */
    suspend fun updateToolApprovalPreferencesCache(
        update: (List<UserToolApprovalPreference>) -> List<UserToolApprovalPreference>
    )
}
