package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import kotlinx.serialization.json.JsonObject

/**
 * Frontend API interface for interacting with tool-related endpoints.
 *
 * This interface defines the operations for managing tool definitions
 * and retrieving session-specific tool configurations. Implementations use the internal HTTP API.
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
     * Creates a new tool definition.
     *
     * Corresponds to `POST /api/v1/tools`.
     * Requires MANAGE_TOOLS permission.
     *
     * @param name The unique name of the tool (machine-readable, used in LLM API calls)
     * @param description A description of what the tool does
     * @param type The type of tool (e.g., WEB_SEARCH, CALCULATOR)
     * @param config Tool-specific configuration (JSON object)
     * @param inputSchema JSON Schema defining expected input parameters
     * @param outputSchema Optional JSON Schema defining expected output structure
     * @param isEnabled Whether the tool is enabled by default
     * @return [Either.Right] containing the newly created [ToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun createTool(
        name: String,
        description: String,
        type: ToolType,
        config: JsonObject,
        inputSchema: JsonObject,
        outputSchema: JsonObject? = null,
        isEnabled: Boolean = true
    ): Either<ApiResourceError, ToolDefinition>

    /**
     * Updates an existing tool definition.
     *
     * Corresponds to `PUT /api/v1/tools/{toolId}`.
     * Requires MANAGE_TOOLS permission.
     *
     * @param tool The [ToolDefinition] object with updated details. The ID must match the path.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found, invalid input).
     */
    suspend fun updateTool(tool: ToolDefinition): Either<ApiResourceError, Unit>

    /**
     * Deletes a tool definition.
     *
     * Corresponds to `DELETE /api/v1/tools/{toolId}`.
     * Requires MANAGE_TOOLS permission.
     *
     * @param toolId The ID of the tool to delete.
     * @return [Either.Right] with [Unit] on successful deletion (typically HTTP 204 No Content),
     *         or [Either.Left] containing a [ApiResourceError] on failure (e.g., not found).
     */
    suspend fun deleteTool(toolId: Long): Either<ApiResourceError, Unit>

    /**
     * Retrieves the list of tools enabled for a specific session.
     *
     * Corresponds to `GET /api/v1/sessions/{sessionId}/tools`.
     *
     * @param sessionId The ID of the session.
     * @return [Either.Right] containing a list of enabled [ToolDefinition] on success,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun getEnabledToolsForSession(sessionId: Long): Either<ApiResourceError, List<ToolDefinition>>

    /**
     * Enables or disables a tool for a specific session.
     *
     * Corresponds to `PUT /api/v1/sessions/{sessionId}/tools/{toolId}`.
     *
     * @param sessionId The ID of the session.
     * @param toolId The ID of the tool.
     * @param enabled Whether to enable (true) or disable (false) the tool for this session.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun setToolEnabledForSession(
        sessionId: Long,
        toolId: Long,
        enabled: Boolean
    ): Either<ApiResourceError, Unit>

    /**
     * Batch enables or disables multiple tools for a specific session.
     *
     * Corresponds to `PUT /api/v1/sessions/{sessionId}/tools`.
     *
     * @param sessionId The ID of the session.
     * @param toolIds List of tool IDs to enable or disable.
     * @param enabled Whether to enable (true) or disable (false) the tools for this session.
     * @return [Either.Right] with [Unit] on successful update,
     *         or [Either.Left] containing a [ApiResourceError] on failure.
     */
    suspend fun setToolsEnabledForSession(
        sessionId: Long,
        toolIds: List<Long>,
        enabled: Boolean
    ): Either<ApiResourceError, Unit>

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

