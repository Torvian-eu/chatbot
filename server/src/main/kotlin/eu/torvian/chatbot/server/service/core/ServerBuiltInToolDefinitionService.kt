package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.ResetServerBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.UpdateServerBuiltInToolError

/**
 * Service interface for managing per-user server built-in tool definitions.
 *
 * Server built-in tools (e.g. `list_agent_roles`) are executed in-process on the server. Each user
 * owns their own `tool_definitions` row per catalog spec, so every operation in this service is
 * user-scoped: a user can only read, modify, or reset their own server built-in tool instances.
 */
interface ServerBuiltInToolDefinitionService {

    /**
     * Retrieves all server built-in tool definitions owned by a specific user.
     *
     * @param userId The owning user identifier.
     * @return List of [ServerBuiltInToolDefinition] (empty if the user owns none).
     */
    suspend fun getServerBuiltInToolsForUser(userId: Long): List<ServerBuiltInToolDefinition>

    /**
     * Updates an existing server built-in tool definition owned by the calling user.
     *
     * Ownership is enforced before any field is persisted: the server built-in tool must belong to
     * [userId], otherwise an [UpdateServerBuiltInToolError.Forbidden] is returned. The public name
     * is prefix-derived and seeder-owned, and the canonical `builtInToolName` is the immutable
     * dispatch key — neither is taken from the request; only the description, input schema,
     * config, and enabled flag are user-editable.
     *
     * @param userId The authenticated user performing the update.
     * @param tool The updated [ServerBuiltInToolDefinition] (id must be set).
     * @return Either an [UpdateServerBuiltInToolError] or the updated [ServerBuiltInToolDefinition].
     */
    suspend fun updateServerBuiltInTool(
        userId: Long,
        tool: ServerBuiltInToolDefinition
    ): Either<UpdateServerBuiltInToolError, ServerBuiltInToolDefinition>

    /**
     * Reconciles the calling user's server built-in tools with the current catalog defaults.
     *
     * Missing catalog specs are created; existing instances have their catalog-derived fields
     * (description, input schema) repaired. Enabled/disabled choices and approval preferences are
     * preserved.
     *
     * @param userId The owning user identifier.
     * @return Either a [ResetServerBuiltInToolsError] or the reconciled list of
     *         [ServerBuiltInToolDefinition].
     */
    suspend fun resetServerBuiltInToolsToDefaults(
        userId: Long
    ): Either<ResetServerBuiltInToolsError, List<ServerBuiltInToolDefinition>>
}
