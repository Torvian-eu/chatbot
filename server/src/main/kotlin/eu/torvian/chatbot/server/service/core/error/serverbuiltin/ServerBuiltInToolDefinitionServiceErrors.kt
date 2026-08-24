package eu.torvian.chatbot.server.service.core.error.serverbuiltin

import eu.torvian.chatbot.server.service.core.error.tool.SeedServerBuiltInToolsError
import eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError

/**
 * Base interface for all server built-in tool definition service errors.
 */
sealed interface ServerBuiltInToolDefinitionServiceError

/**
 * Errors that can occur when updating a server built-in tool definition.
 */
sealed interface UpdateServerBuiltInToolError : ServerBuiltInToolDefinitionServiceError {
    /**
     * The requested server built-in tool was not found.
     *
     * @property toolId The tool-definition identifier that was not found.
     */
    data class ToolNotFound(val toolId: Long) : UpdateServerBuiltInToolError

    /**
     * The authenticated user is not the owner of the referenced server built-in tool.
     *
     * The owner id is deliberately NOT carried here: the 403 response must not let a probing user
     * confirm the tool exists or learn who owns it, so the failure is shaped exactly like a
     * not-found from the caller's perspective.
     *
     * @property toolId The tool-definition identifier the user tried to modify.
     */
    data class Forbidden(val toolId: Long) : UpdateServerBuiltInToolError

    /**
     * The tool definition failed validation.
     *
     * @property error The validation error.
     */
    data class ValidationError(val error: ValidateToolError) : UpdateServerBuiltInToolError
}

/**
 * Errors that can occur when resetting a user's server built-in tools to catalog defaults.
 */
sealed interface ResetServerBuiltInToolsError : ServerBuiltInToolDefinitionServiceError {
    /**
     * The reconciliation with the catalog failed while creating or repairing tool definitions.
     *
     * @property error The underlying [SeedServerBuiltInToolsError] describing the failure.
     */
    data class SeedFailed(val error: SeedServerBuiltInToolsError) : ResetServerBuiltInToolsError
}
