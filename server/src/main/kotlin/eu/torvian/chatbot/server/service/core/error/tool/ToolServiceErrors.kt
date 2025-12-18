package eu.torvian.chatbot.server.service.core.error.tool

import kotlinx.serialization.json.JsonObject

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

/**
 * Errors that can occur when batch setting tool enablement for a session.
 */
sealed interface SetToolsEnabledError : ToolServiceError {
    /**
     * Foreign key constraint violation - either the session or one or more tools don't exist.
     * @property sessionId The ID of the session that was part of the batch request.
     * @property toolIds The IDs of the tools that were part of the batch request.
     */
    data class InvalidReference(val sessionId: Long, val toolIds: List<Long>) : SetToolsEnabledError
}

sealed interface ValidateToolError : ToolServiceError {
    /**
     * The tool name is invalid (empty, blank, or too long).
     * @property message Description of the validation error.
     */
    data class InvalidName(val message: String, val name: String) : ValidateToolError

    /**
     * The tool description is invalid (empty or too long).
     * @property message Description of the validation error.
     */
    data class InvalidDescription(val message: String, val description: String) : ValidateToolError

    /**
     * The input schema JSON is invalid or not a valid JSON Schema.
     * @property message Description of the validation error.
     */
    data class InvalidInputSchema(val message: String, val schema: JsonObject) : ValidateToolError

    /**
     * The output schema JSON is invalid or not a valid JSON Schema.
     * @property message Description of the validation error.
     */
    data class InvalidOutputSchema(val message: String, val schema: JsonObject) : ValidateToolError
}

/**
 * Errors that can occur when setting a tool approval preference.
 */
sealed interface SetToolApprovalPreferenceError : ToolServiceError {
    /**
     * Indicates that the preference could not be set because a related entity (user or tool)
     * does not exist.
     *
     * @property message Descriptive error message
     */
    data class InvalidRelatedEntity(val message: String) : SetToolApprovalPreferenceError
}

/**
 * Errors that can occur when retrieving a tool approval preference.
 */
sealed interface GetToolApprovalPreferenceError : ToolServiceError {
    /**
     * The approval preference was not found.
     * @property userId The user ID.
     * @property toolDefinitionId The tool definition ID.
     */
    data class PreferenceNotFound(val userId: Long, val toolDefinitionId: Long) : GetToolApprovalPreferenceError
}

/**
 * Errors that can occur when deleting a tool approval preference.
 */
sealed interface DeleteToolApprovalPreferenceError : ToolServiceError {
    /**
     * The approval preference was not found.
     * @property userId The user ID.
     * @property toolDefinitionId The tool definition ID.
     */
    data class PreferenceNotFound(val userId: Long, val toolDefinitionId: Long) : DeleteToolApprovalPreferenceError
}
