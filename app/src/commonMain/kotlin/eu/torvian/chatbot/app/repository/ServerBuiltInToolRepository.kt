package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing the current user's Server Built-In Tool definitions.
 *
 * Server built-in tools (e.g. `list_agent_roles`) are per-user instances executed in-process on the
 * server. This repository handles their loading, updating, and resetting separately from the general
 * [ToolRepository] and [LocalMCPToolRepository], mirroring the separation used for operator tools.
 *
 * **Separation of Concerns**:
 * - ToolRepository = All tools
 * - LocalMCPToolRepository = MCP tools (grouped by server)
 * - BuiltInToolRepository = Built-in worker tools (grouped by worker)
 * - OperatorToolRepository = Operator tools (scoped to the current user)
 * - ServerBuiltInToolRepository = Server built-in tools (scoped to the current user)
 */
interface ServerBuiltInToolRepository {

    /**
     * Reactive stream of server built-in tool definitions owned by the current user.
     *
     * This StateFlow provides real-time updates whenever the server built-in tool data changes,
     * allowing ViewModels and other consumers to automatically react to data changes without manual
     * refresh operations.
     *
     * @return StateFlow containing the current state of server built-in tools wrapped in DataState.
     */
    val serverBuiltInTools: StateFlow<DataState<RepositoryError, List<ServerBuiltInToolDefinition>>>

    /**
     * Loads all server built-in tool definitions owned by the current user from the server.
     *
     * Fetches the per-user server built-in tools and updates the cache.
     *
     * @return [Either.Right] with the loaded tools on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun loadTools(): Either<RepositoryError, Unit>

    /**
     * Updates an existing server built-in tool, typically to toggle its enabled state.
     *
     * @param tool The updated [ServerBuiltInToolDefinition].
     * @return [Either.Right] with the updated tool on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun updateServerBuiltInTool(
        tool: ServerBuiltInToolDefinition
    ): Either<RepositoryError, ServerBuiltInToolDefinition>

    /**
     * Resets the current user's server built-in tools to the catalog defaults on the server.
     *
     * Reconciles the user's server built-in tools with the server catalog: missing tools are
     * created and existing tools have their catalog-derived fields repaired, while
     * enabled/disabled choices and approval preferences are preserved. On success the in-memory
     * cache is refreshed with the returned tool list.
     *
     * @return [Either.Right] with the reset tools on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun resetToDefaults(): Either<RepositoryError, List<ServerBuiltInToolDefinition>>
}
