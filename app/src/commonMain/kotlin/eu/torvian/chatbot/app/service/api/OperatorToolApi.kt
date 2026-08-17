package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition

/**
 * Frontend API interface for interacting with Operator Tool endpoints.
 *
 * This interface defines the operations for managing the authenticated user's per-user operator
 * tool definitions (e.g. `spawn_agent`). Implementations use the internal HTTP API. All methods are
 * suspend functions and return [Either<ApiResourceError, T>].
 */
interface OperatorToolApi {
    /**
     * Retrieves all operator tool definitions owned by the current user.
     *
     * Corresponds to `GET /api/v1/operator-tools`.
     *
     * @return [Either.Right] containing a list of [OperatorToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getOperatorTools(): Either<ApiResourceError, List<OperatorToolDefinition>>

    /**
     * Updates an existing operator tool, typically to toggle its enabled state.
     *
     * Corresponds to `PUT /api/v1/operator-tools/{toolId}`.
     *
     * @param tool The [OperatorToolDefinition] with updated details (id must be set).
     * @return [Either.Right] containing the updated [OperatorToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, forbidden).
     */
    suspend fun updateOperatorTool(tool: OperatorToolDefinition): Either<ApiResourceError, OperatorToolDefinition>

    /**
     * Resets the current user's operator tools to the catalog defaults.
     *
     * Corresponds to `POST /api/v1/operator-tools/reset`.
     *
     * @return [Either.Right] containing the reset list of [OperatorToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun resetOperatorToolsToDefaults(): Either<ApiResourceError, List<OperatorToolDefinition>>
}
