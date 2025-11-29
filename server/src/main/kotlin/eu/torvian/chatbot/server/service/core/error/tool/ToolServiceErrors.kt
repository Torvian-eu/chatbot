package eu.torvian.chatbot.server.service.core.error.tool

/**
 * Base interface for all tool-related service errors.
 */
sealed interface ToolServiceError

/**
 * Errors that can occur when retrieving a tool by ID.
 */
sealed interface GetToolError : ToolServiceError {
    /**
     * The requested tool was not found.
     * @property id The ID of the tool that was not found.
     */
    data class ToolNotFound(val id: Long) : GetToolError
}

/**
 * Errors that can occur when updating an existing tool.
 */
sealed interface UpdateToolError : ToolServiceError {
    /**
     * The tool failed validation.
     * @property error The validation error.
     */
    data class ValidationError(val error: ValidateToolError) : UpdateToolError

    /**
     * The requested tool was not found.
     * @property id The ID of the tool that was not found.
     */
    data class ToolNotFound(val id: Long) : UpdateToolError
}

/**
 * Errors that can occur when deleting a tool.
 */
sealed interface DeleteToolError : ToolServiceError {
    /**
     * The requested tool was not found.
     * @property id The ID of the tool that was not found.
     */
    data class ToolNotFound(val id: Long) : DeleteToolError
}

/**
 * Errors that can occur when setting tool enablement for a session.
 */
sealed interface SetToolEnabledError : ToolServiceError {
    /**
     * The requested session was not found.
     * @property id The ID of the session that was not found.
     */
    data class SessionNotFound(val id: Long) : SetToolEnabledError

    /**
     * The requested tool was not found.
     * @property id The ID of the tool that was not found.
     */
    data class ToolNotFound(val id: Long) : SetToolEnabledError
}

sealed interface ValidateToolError : ToolServiceError {
    /**
     * The tool name is invalid (empty, blank, or too long).
     * @property message Description of the validation error.
     */
    data class InvalidName(val message: String) : ValidateToolError

    /**
     * The tool description is invalid (empty or too long).
     * @property message Description of the validation error.
     */
    data class InvalidDescription(val message: String) : ValidateToolError

    /**
     * The input schema JSON is invalid or not a valid JSON Schema.
     * @property message Description of the validation error.
     */
    data class InvalidInputSchema(val message: String) : ValidateToolError

    /**
     * The output schema JSON is invalid or not a valid JSON Schema.
     * @property message Description of the validation error.
     */
    data class InvalidOutputSchema(val message: String) : ValidateToolError
}

