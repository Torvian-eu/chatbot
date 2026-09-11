package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference

/**
 * Frontend API interface for interacting with tool-related endpoints.
 *
 * This interface defines the operations for managing tool definitions
 * and user tool approval preferences. Implementations use the internal HTTP API.
 * All methods are suspend functions and return [Either<ApiResourceError, T>].
 */
interface ToolApi {
    /**
     * Retrieves a list of all available tool definitions for the current user.
     *
     * Corresponds to `GET /api/v1/tools`.
     *
     * Returns a combination of:
     * - All global tools (non-MCP_LOCAL type)
     * - User-specific MCP_LOCAL tools (where the MCP server is owned by the current user)
     *
     * @return [Either.Right] containing a list of [ToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getAllTools(): Either<ApiResourceError, List<ToolDefinition>>

    /**
     * Retrieves details for a specific tool definition.
     *
     * Corresponds to `GET /api/v1/tools/{toolId}`.
     *
     * @param toolId The ID of the tool to retrieve.
     * @return [Either.Right] containing the requested [ToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getToolById(toolId: Long): Either<ApiResourceError, ToolDefinition>

    /**
     * Retrieves the current user's approval preferences for all tools.
     *
     * Corresponds to `GET /api/v1/tools/approval-preferences`.
     *
     * @return [Either.Right] containing a list of [UserToolApprovalPreference] on success,
     *   or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getAllToolApprovalPreferences(): Either<ApiResourceError, List<UserToolApprovalPreference>>

    /**
     * Retrieves the current user's approval preference for a specific tool.
     *
     * Corresponds to `GET /api/v1/tools/approval-preferences/{toolId}`.
     *
     * @param toolId The ID of the tool.
     * @return [Either.Right] containing the [UserToolApprovalPreference] on success,
     *   or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun getToolApprovalPreference(toolId: Long): Either<ApiResourceError, UserToolApprovalPreference>

    /**
     * Sets the current user's approval preference for a specific tool.
     *
     * Corresponds to `PUT /api/v1/tools/approval-preferences/{toolId}`.
     *
     * @param toolDefinitionId The ID of the tool definition.
     * @param autoApprove Whether to auto-approve (true) or auto-deny (false) this tool
     * @param conditions Optional JSON string for conditional auto-approval logic (reserved for future use)
     * @param denialReason Optional reason text for auto-denials, to be read by the LLM (reserved for future use)
     * @return [Either.Right] with [UserToolApprovalPreference] on successful update,
     *   or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun setToolApprovalPreference(
        toolDefinitionId: Long,
        autoApprove: Boolean,
        conditions: String? = null,
        denialReason: String? = null
    ): Either<ApiResourceError, UserToolApprovalPreference>

    /**
     * Deletes the current user's approval preference for a specific tool.
     *
     * Corresponds to `DELETE /api/v1/tools/approval-preferences/{toolId}`.
     *
     * @param toolId The ID of the tool.
     * @return [Either.Right] with [Unit] on successful deletion,
     *   or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun deleteToolApprovalPreference(toolId: Long): Either<ApiResourceError, Unit>
}
