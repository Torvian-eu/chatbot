package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing Built-in Worker Tool definitions.
 *
 * This repository handles built-in worker tool operations, separate from the general
 * [ToolRepository] and [LocalMCPToolRepository]. It provides caching and reactive state
 * management for the built-in tools owned by a specific worker.
 *
 * **Separation of Concerns**:
 * - ToolRepository = All tools
 * - LocalMCPToolRepository = MCP tools (grouped by server)
 * - BuiltInToolRepository = Built-in worker tools (grouped by worker)
 */
interface BuiltInToolRepository {

    /**
     * Reactive stream of built-in worker tools for the most recently loaded worker.
     *
     * This StateFlow provides real-time updates whenever the built-in tool data for the
     * active worker changes, allowing ViewModels and other consumers to automatically react
     * to data changes without manual refresh operations.
     *
     * @return StateFlow containing the current state of built-in tools wrapped in DataState.
     */
    val builtInTools: StateFlow<DataState<RepositoryError, List<BuiltInWorkerToolDefinition>>>

    /**
     * Loads all built-in worker tools for the given worker from the server.
     *
     * Fetches the built-in tools seeded for the specified worker and updates the cache.
     *
     * @param workerId The unique identifier of the worker whose built-in tools should be loaded.
     * @return [Either.Right] with the loaded tools on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun loadTools(workerId: Long): Either<RepositoryError, List<BuiltInWorkerToolDefinition>>

    /**
     * Updates an existing built-in worker tool, typically to toggle its enabled state.
     *
     * @param tool The updated [BuiltInWorkerToolDefinition].
     * @return [Either.Right] with the updated tool on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun updateBuiltInTool(tool: BuiltInWorkerToolDefinition): Either<RepositoryError, BuiltInWorkerToolDefinition>

    /**
     * Resets the built-in tools of the given worker to the catalog defaults on the server.
     *
     * Reconciles the worker's tools with the server catalog: missing tools are created and
     * existing tools have their catalog-derived fields repaired, while enabled/disabled choices
     * and approval preferences are preserved. On success the in-memory cache is refreshed with the
     * returned tool list.
     *
     * @param workerId The unique identifier of the worker whose built-in tools should be reset.
     * @return [Either.Right] with the reset tools on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun resetToDefaults(workerId: Long): Either<RepositoryError, List<BuiltInWorkerToolDefinition>>
}
