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
 * Errors that can occur when creating a new tool.
 */
sealed interface CreateToolError : ToolServiceError {
    /**
     * The tool name is invalid (empty, blank, or too long).
     * @property message Description of the validation error.
     */
    data class InvalidName(val message: String) : CreateToolError

    /**
     * The tool description is invalid (empty or too long).
     * @property message Description of the validation error.
     */
    data class InvalidDescription(val message: String) : CreateToolError

    /**
     * The tool configuration JSON is invalid.
     * @property message Description of the validation error.
     */
    data class InvalidConfig(val message: String) : CreateToolError

    /**
     * The input schema JSON is invalid or not a valid JSON Schema.
     * @property message Description of the validation error.
     */
    data class InvalidInputSchema(val message: String) : CreateToolError

    /**
     * A tool with the same name already exists.
     * @property name The name that conflicts.
     */
    data class DuplicateName(val name: String) : CreateToolError

    /**
     * An error occurred while persisting the tool.
     * @property message Description of the persistence error.
     */
    data class PersistenceError(val message: String) : CreateToolError
}

/**
 * Errors that can occur when updating an existing tool.
 */
sealed interface UpdateToolError : ToolServiceError {
    /**
     * The requested tool was not found.
     * @property id The ID of the tool that was not found.
     */
    data class ToolNotFound(val id: Long) : UpdateToolError

    /**
     * The tool name is invalid (empty, blank, or too long).
     * @property message Description of the validation error.
     */
    data class InvalidName(val message: String) : UpdateToolError

    /**
     * The tool description is invalid (empty or too long).
     * @property message Description of the validation error.
     */
    data class InvalidDescription(val message: String) : UpdateToolError

    /**
     * The tool configuration JSON is invalid.
     * @property message Description of the validation error.
     */
    data class InvalidConfig(val message: String) : UpdateToolError

    /**
     * The input schema JSON is invalid or not a valid JSON Schema.
     * @property message Description of the validation error.
     */
    data class InvalidInputSchema(val message: String) : UpdateToolError

    /**
     * A tool with the same name already exists.
     * @property name The name that conflicts.
     */
    data class DuplicateName(val name: String) : UpdateToolError

    /**
     * An error occurred while persisting the tool update.
     * @property message Description of the persistence error.
     */
    data class PersistenceError(val message: String) : UpdateToolError
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

    /**
     * The tool is currently in use and cannot be deleted.
     * @property message Description of the constraint.
     */
    data class ToolInUse(val message: String) : DeleteToolError
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

    /**
     * An error occurred while persisting the configuration.
     * @property message Description of the persistence error.
     */
    data class PersistenceError(val message: String) : SetToolEnabledError
}

