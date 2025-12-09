package eu.torvian.chatbot.server.service.core.error.mcp

import eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError

/**
 * Base interface for all Local MCP Tool Definition related service errors.
 */
sealed interface LocalMCPToolDefinitionServiceError

/**
 * Errors that can occur when creating MCP tools.
 */
sealed interface CreateMCPToolsError : LocalMCPToolDefinitionServiceError {
    /**
     * A tool failed validation.
     * @property validationError The validation error.
     */
    data class ToolValidationError(val validationError: ValidateToolError) : CreateMCPToolsError

    /**
     * A tool with the same name already exists within the server's tools.
     * @property name The name that conflicts.
     */
    data class DuplicateName(val name: String) : CreateMCPToolsError

    /**
     * Represents an unspecified error that occurred during the creation of MCP tools.
     *
     * @property message A description of the error.
     */
    data class OtherError(val message: String) : CreateMCPToolsError
}

/**
 * Errors that can occur when retrieving MCP tools by server ID.
 */
sealed interface GetMCPToolsByServerIdError : LocalMCPToolDefinitionServiceError {
    /**
     * The specified server was not found.
     * @property serverId The ID of the server that was not found.
     */
    data class ServerNotFound(val serverId: Long) : GetMCPToolsByServerIdError
}

/**
 * Errors that can occur when retrieving a single MCP tool by ID.
 */
sealed interface GetMCPToolByIdError : LocalMCPToolDefinitionServiceError {
    /**
     * The requested tool was not found.
     * @property toolId The ID of the tool that was not found.
     */
    data class ToolNotFound(val toolId: Long) : GetMCPToolByIdError
}

/**
 * Errors that can occur when updating an MCP tool.
 */
sealed interface UpdateMCPToolError : LocalMCPToolDefinitionServiceError {
    /**
     * The requested tool was not found.
     * @property toolId The ID of the tool that was not found.
     */
    data class ToolNotFound(val toolId: Long) : UpdateMCPToolError

    /**
     * A tool with the same name already exists within the server's tools.
     * @property name The name that conflicts.
     */
    data class DuplicateName(val name: String) : UpdateMCPToolError

    /**
     * The tool failed validation.
     * @property error The validation error.
     */
    data class ValidationError(val error: ValidateToolError) : UpdateMCPToolError
}

/**
 * Errors that can occur when deleting MCP tools for a server.
 */
sealed interface DeleteMCPToolsForServerError : LocalMCPToolDefinitionServiceError {
    /**
     * The specified server was not found.
     * @property serverId The ID of the server that was not found.
     */
    data class ServerNotFound(val serverId: Long) : DeleteMCPToolsForServerError
}

/**
 * Errors that can occur when refreshing MCP tools for a server.
 */
sealed interface RefreshMCPToolsError : LocalMCPToolDefinitionServiceError {
    /**
     * The specified server was not found.
     * @property serverId The ID of the server that was not found.
     */
    data class ServerNotFound(val serverId: Long) : RefreshMCPToolsError

    /**
     * A tool with the same name already exists within the server's tools.
     * @property name The name that conflicts.
     */
    data class DuplicateName(val name: String) : RefreshMCPToolsError

    /**
     * A tool failed validation.
     * @property validationError The validation error.
     */
    data class ToolValidationError(val validationError: ValidateToolError) : RefreshMCPToolsError

    /**
     * Represents an unspecified error that occurred during the refresh of MCP tools.
     *
     * @property message A description of the error.
     */
    data class OtherError(val message: String) : RefreshMCPToolsError
}

