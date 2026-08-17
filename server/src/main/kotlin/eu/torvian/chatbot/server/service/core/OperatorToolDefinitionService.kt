package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.server.service.core.error.operator.ResetOperatorToolsError
import eu.torvian.chatbot.server.service.core.error.operator.UpdateOperatorToolError

/**
 * Service interface for managing per-user operator tool definitions.
 *
 * Operator tools (e.g. `spawn_agent`) are executed by the operator over the chat WebSocket. Each
 * user owns their own `tool_definitions` row per catalog spec, so every operation in this service is
 * user-scoped: a user can only read, modify, or reset their own operator tool instances.
 */
interface OperatorToolDefinitionService {

    /**
     * Retrieves all operator tool definitions owned by a specific user.
     *
     * @param userId The owning user identifier.
     * @return List of [OperatorToolDefinition] (empty if the user owns none).
     */
    suspend fun getOperatorToolsForUser(userId: Long): List<OperatorToolDefinition>

    /**
     * Updates an existing operator tool definition owned by the calling user.
     *
     * Ownership is enforced before any field is persisted: the operator tool must belong to
     * [userId], otherwise a [UpdateOperatorToolError.Forbidden] is returned.
     *
     * @param userId The authenticated user performing the update.
     * @param tool The updated [OperatorToolDefinition] (id must be set).
     * @return Either an [UpdateOperatorToolError] or the updated [OperatorToolDefinition].
     */
    suspend fun updateOperatorTool(
        userId: Long,
        tool: OperatorToolDefinition
    ): Either<UpdateOperatorToolError, OperatorToolDefinition>

    /**
     * Reconciles the calling user's operator tools with the current catalog defaults.
     *
     * Missing catalog specs are created; existing instances have their catalog-derived fields
     * (description, input schema) repaired. Enabled/disabled choices and approval preferences are
     * preserved.
     *
     * @param userId The owning user identifier.
     * @return Either a [ResetOperatorToolsError] or the reconciled list of [OperatorToolDefinition].
     */
    suspend fun resetOperatorToolsToDefaults(
        userId: Long
    ): Either<ResetOperatorToolsError, List<OperatorToolDefinition>>
}
