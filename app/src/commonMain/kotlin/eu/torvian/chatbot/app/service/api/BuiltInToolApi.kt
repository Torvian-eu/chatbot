package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition

/**
 * Frontend API interface for interacting with Built-in Worker Tool endpoints.
 *
 * This interface defines the operations for managing built-in tool definitions that are
 * provided by a specific worker. Implementations use the internal HTTP API. All methods are
 * suspend functions and return [Either<ApiResourceError, T>].
 *
 * **Note**: This API is separate from [ToolApi] and [LocalMCPToolApi] and handles
 * built-in worker tool operations, which are scoped to a single worker instance.
 */
interface BuiltInToolApi {
    /**
     * Retrieves all built-in worker tools for a specific worker.
     *
     * Corresponds to `GET /api/v1/built-in-tools/worker/{workerId}`.
     *
     * @param workerId The unique identifier of the worker whose built-in tools should be listed.
     * @return [Either.Right] containing a list of [BuiltInWorkerToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getBuiltInToolsForWorker(workerId: Long): Either<ApiResourceError, List<BuiltInWorkerToolDefinition>>

    /**
     * Retrieves a single built-in worker tool by its definition ID.
     *
     * Corresponds to `GET /api/v1/built-in-tools/{toolId}`.
     *
     * @param toolId The unique identifier of the built-in tool definition.
     * @return [Either.Right] containing the [BuiltInWorkerToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getBuiltInToolById(toolId: Long): Either<ApiResourceError, BuiltInWorkerToolDefinition>

    /**
     * Updates an existing built-in worker tool, typically to toggle its enabled state.
     *
     * Corresponds to `PUT /api/v1/built-in-tools/{toolId}`.
     *
     * @param tool The [BuiltInWorkerToolDefinition] with updated details (id must be set).
     * @return [Either.Right] containing the updated [BuiltInWorkerToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, invalid input).
     */
    suspend fun updateBuiltInTool(tool: BuiltInWorkerToolDefinition): Either<ApiResourceError, BuiltInWorkerToolDefinition>

    /**
     * Resets a worker's built-in tools to the catalog defaults.
     *
     * Corresponds to `POST /api/v1/built-in-tools/worker/{workerId}/reset`.
     *
     * @param workerId The unique identifier of the worker whose built-in tools should be reset.
     * @return [Either.Right] containing the reset list of [BuiltInWorkerToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g. not found, forbidden).
     */
    suspend fun resetBuiltInToolsToDefaults(workerId: Long): Either<ApiResourceError, List<BuiltInWorkerToolDefinition>>
}
