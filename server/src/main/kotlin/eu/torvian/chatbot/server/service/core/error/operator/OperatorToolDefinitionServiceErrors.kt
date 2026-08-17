package eu.torvian.chatbot.server.service.core.error.operator

import eu.torvian.chatbot.server.service.core.error.tool.SeedOperatorToolsError
import eu.torvian.chatbot.server.service.core.error.tool.ValidateToolError

/**
 * Base interface for all operator tool definition service errors.
 */
sealed interface OperatorToolDefinitionServiceError

/**
 * Errors that can occur when updating an operator tool definition.
 */
sealed interface UpdateOperatorToolError : OperatorToolDefinitionServiceError {
    /**
     * The requested operator tool was not found.
     *
     * @property toolId The tool-definition identifier that was not found.
     */
    data class ToolNotFound(val toolId: Long) : UpdateOperatorToolError

    /**
     * The authenticated user is not the owner of the referenced operator tool.
     *
     * @property toolId The tool-definition identifier the user tried to modify.
     * @property ownerUserId The actual owning user identifier of the operator tool.
     */
    data class Forbidden(val toolId: Long, val ownerUserId: Long) : UpdateOperatorToolError

    /**
     * The tool definition failed validation.
     *
     * @property error The validation error.
     */
    data class ValidationError(val error: ValidateToolError) : UpdateOperatorToolError
}

/**
 * Errors that can occur when resetting a user's operator tools to catalog defaults.
 */
sealed interface ResetOperatorToolsError : OperatorToolDefinitionServiceError {
    /**
     * The reconciliation with the catalog failed while creating or repairing tool definitions.
     *
     * @property error The underlying [SeedOperatorToolsError] describing the failure.
     */
    data class SeedFailed(val error: SeedOperatorToolsError) : ResetOperatorToolsError
}
