package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.MiscToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.service.core.error.tool.*
import kotlinx.serialization.json.JsonObject

/**
 * Service interface for managing tool definitions and configurations.
 * Contains core business logic related to tools, independent of API or data access details.
 */
interface ToolService {
    /**
     * Retrieves all tool definitions from the system.
     * @return A list of all [MiscToolDefinition] objects. Returns an empty list if no tools exist.
     */
    suspend fun getAllTools(): List<MiscToolDefinition>

    /**
     * Retrieves a specific tool definition by ID.
     * @param id The ID of the tool to retrieve.
     * @return Either a [GetToolError] if the tool doesn't exist, or the [MiscToolDefinition].
     */
    suspend fun getToolById(id: Long): Either<GetToolError, MiscToolDefinition>

    /**
     * Creates a new tool definition.
     * Validates the tool name, description, config, and input schema before persisting.
     * @param name The unique name of the tool.
     * @param description A description of what the tool does.
     * @param type The type of the tool (e.g., WEB_SEARCH, CALCULATOR).
     * @param config JSON configuration specific to the tool type.
     * @param inputSchema JSON Schema describing the expected input parameters.
     * @param outputSchema Optional JSON Schema describing the output format.
     * @param isEnabled Whether the tool is enabled by default.
     * @return Either a [ValidateToolError] if validation fails,
     *         or the newly created [ToolDefinition].
     */
    suspend fun createTool(
        name: String,
        description: String,
        type: ToolType,
        config: JsonObject,
        inputSchema: JsonObject,
        outputSchema: JsonObject?,
        isEnabled: Boolean
    ): Either<ValidateToolError, MiscToolDefinition>

    /**
     * Updates an existing tool definition.
     * Validates all fields before persisting changes.
     * @param tool The updated tool definition.
     * @return Either an [UpdateToolError] if validation or update fails, or [ToolDefinition] if successful.
     */
    suspend fun updateTool(tool: ToolDefinition): Either<UpdateToolError, ToolDefinition>

    /**
     * Deletes a tool definition.
     * Cannot delete tools that are currently in use in sessions.
     * @param id The ID of the tool to delete.
     * @return Either a [DeleteToolError] if the tool doesn't exist or is in use,
     *         or Unit if successful.
     */
    suspend fun deleteTool(id: Long): Either<DeleteToolError, Unit>

    /**
     * Retrieves all tools that are enabled for a specific session.
     * Returns both globally enabled tools and session-specific overrides.
     * @param sessionId The ID of the session.
     * @return A list of [ToolDefinition] objects enabled for this session.
     */
    suspend fun getEnabledToolsForSession(sessionId: Long): List<ToolDefinition>

    /**
     * Enables or disables a specific tool for a session.
     * This creates a session-specific override of the tool's default enabled state.
     * @param sessionId The ID of the session.
     * @param toolId The ID of the tool.
     * @param enabled Whether to enable or disable the tool for this session.
     * @return Either a [SetToolEnabledError] if the session or tool doesn't exist,
     *         or Unit if successful.
     */
    suspend fun setToolEnabledForSession(
        sessionId: Long,
        toolId: Long,
        enabled: Boolean
    ): Either<SetToolEnabledError, Unit>

    /**
     * Validates a tool definition without persisting it.
     * @param tool The tool definition to validate.
     * @return Either a [ValidateToolError] if validation fails, or Unit if successful.
     */
    suspend fun validateToolDefinition(tool: ToolDefinition): Either<ValidateToolError, Unit>

    /**
     * Validates a tool definition without persisting it.
     * @param name The name of the tool.
     * @param description The description of the tool.
     * @param inputSchema The input schema of the tool.
     * @param outputSchema The output schema of the tool.
     * @return Either a [ValidateToolError] if validation fails, or Unit if successful.
     */
    suspend fun validateToolDefinition(
        name: String,
        description: String,
        inputSchema: JsonObject,
        outputSchema: JsonObject?
    ): Either<ValidateToolError, Unit>
}

