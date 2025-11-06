package eu.torvian.chatbot.server.data.dao.error

/**
 * Error types for tool definition retrieval operations.
 */
sealed class ToolDefinitionError {
    /**
     * Indicates that a tool definition with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : ToolDefinitionError()

    /**
     * Indicates that a tool definition with the specified name was not found.
     *
     * @property name The name that was not found
     */
    data class NameNotFound(val name: String) : ToolDefinitionError()
}

/**
 * Error types for tool definition insertion operations.
 */
sealed class InsertToolDefinitionError {
    /**
     * Indicates that a tool with this name already exists (unique constraint violation).
     *
     * @property name The duplicate name
     */
    data class DuplicateName(val name: String) : InsertToolDefinitionError()

    /**
     * Indicates that the provided JSON schema is invalid.
     *
     * @property reason Description of why the schema is invalid
     */
    data class InvalidSchema(val reason: String) : InsertToolDefinitionError()
}

/**
 * Error types for tool definition update operations.
 */
sealed class UpdateToolDefinitionError {
    /**
     * Indicates that the tool definition to update was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : UpdateToolDefinitionError()

    /**
     * Indicates that updating to this name would violate the unique constraint.
     *
     * @property name The duplicate name
     */
    data class DuplicateName(val name: String) : UpdateToolDefinitionError()

    /**
     * Indicates that the provided JSON schema is invalid.
     *
     * @property reason Description of why the schema is invalid
     */
    data class InvalidSchema(val reason: String) : UpdateToolDefinitionError()
}

/**
 * Error types for tool definition deletion operations.
 */
sealed class DeleteToolDefinitionError {
    /**
     * Indicates that the tool definition to delete was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : DeleteToolDefinitionError()
}

