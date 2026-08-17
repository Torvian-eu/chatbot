package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing the current user's Operator Tool definitions.
 *
 * Operator tools (e.g. `spawn_agent`) are per-user instances executed by the operator over the chat
 * WebSocket. This repository handles their loading, updating, and resetting separately from the
 * general [ToolRepository] and [LocalMCPToolRepository], mirroring the separation used for
 * built-in worker tools.
 *
 * **Separation of Concerns**:
 * - ToolRepository = All tools
 * - LocalMCPToolRepository = MCP tools (grouped by server)
 * - BuiltInToolRepository = Built-in worker tools (grouped by worker)
 * - OperatorToolRepository = Operator tools (scoped to the current user)
 */
interface OperatorToolRepository {

    /**
     * Reactive stream of operator tool definitions owned by the current user.
     *
     * This StateFlow provides real-time updates whenever the operator tool data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of operator tools wrapped in DataState.
     */
    val operatorTools: StateFlow<DataState<RepositoryError, List<OperatorToolDefinition>>>

    /**
     * Loads all operator tool definitions owned by the current user from the server.
     *
     * Fetches the per-user operator tools and updates the cache.
     *
     * @return [Either.Right] with the loaded tools on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun loadTools(): Either<RepositoryError, Unit>

    /**
     * Updates an existing operator tool, typically to toggle its enabled state.
     *
     * @param tool The updated [OperatorToolDefinition].
     * @return [Either.Right] with the updated tool on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun updateOperatorTool(tool: OperatorToolDefinition): Either<RepositoryError, OperatorToolDefinition>

    /**
     * Resets the current user's operator tools to the catalog defaults on the server.
     *
     * Reconciles the user's operator tools with the server catalog: missing tools are created and
     * existing tools have their catalog-derived fields repaired, while enabled/disabled choices
     * and approval preferences are preserved. On success the in-memory cache is refreshed with the
     * returned tool list.
     *
     * @return [Either.Right] with the reset tools on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun resetToDefaults(): Either<RepositoryError, List<OperatorToolDefinition>>
}
