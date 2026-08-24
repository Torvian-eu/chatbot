package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition

/**
 * Frontend API interface for interacting with Server Built-In Tool endpoints.
 *
 * This interface defines the operations for managing the authenticated user's per-user server
 * built-in tool definitions (e.g. `list_agent_roles`). Implementations use the internal HTTP API.
 * All methods are suspend functions and return [Either<ApiResourceError, T>].
 */
interface ServerBuiltInToolApi {
    /**
     * Retrieves all server built-in tool definitions owned by the current user.
     *
     * Corresponds to `GET /api/v1/server-built-in-tools`.
     *
     * @return [Either.Right] containing a list of [ServerBuiltInToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getServerBuiltInTools(): Either<ApiResourceError, List<ServerBuiltInToolDefinition>>

    /**
     * Updates an existing server built-in tool, typically to toggle its enabled state.
     *
     * Corresponds to `PUT /api/v1/server-built-in-tools/{toolId}`.
     *
     * @param tool The [ServerBuiltInToolDefinition] with updated details (id must be set).
     * @return [Either.Right] containing the updated [ServerBuiltInToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, forbidden).
     */
    suspend fun updateServerBuiltInTool(
        tool: ServerBuiltInToolDefinition
    ): Either<ApiResourceError, ServerBuiltInToolDefinition>

    /**
     * Resets the current user's server built-in tools to the catalog defaults.
     *
     * Corresponds to `POST /api/v1/server-built-in-tools/reset`.
     *
     * @return [Either.Right] containing the reset list of [ServerBuiltInToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun resetServerBuiltInToolsToDefaults(): Either<ApiResourceError, List<ServerBuiltInToolDefinition>>
}
